package andritz.sscm.forensics;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.SurroundSCM;
import hudson.scm.SurroundSCMAnnotation;
import hudson.scm.SurroundSCMUser;
import io.jenkins.plugins.forensics.blame.Blamer;
import io.jenkins.plugins.forensics.blame.Blames;
import io.jenkins.plugins.forensics.blame.FileBlame;
import io.jenkins.plugins.forensics.blame.FileBlame.FileBlameBuilder;
import io.jenkins.plugins.forensics.blame.FileLocations;
import edu.hm.hafner.util.FilteredLog;

public class SurroundSCMBlamer extends Blamer {
   private static final long serialVersionUID = 3013015086648085760L;

   private final SurroundSCM sscm;
   private final EnvVars environment;
   private final FilePath workspace;
   private final TaskListener listener;
   private final Map<String,SurroundSCMUser> userByName;

   public SurroundSCMBlamer(SurroundSCM sscm, final Run<?, ?> build, final EnvVars environment, final FilePath workspace, final TaskListener listener) {
      this.sscm = sscm;
      this.environment = environment;
      this.workspace = workspace;
      this.listener = listener;
      this.userByName = new HashMap<>();
      this.sscm.saveCredentialsAndExeForBlames(build, environment, workspace, listener);
   }

   @Override
   public Blames blame(FileLocations locations, FilteredLog logger) {
      Blames blames = new Blames();
      if (locations.isEmpty()) {
         return blames;
      }

      logger.logInfo("Invoking SurroundSCM blamer to create author and version information for %d affected files", locations.size());

      String workspacePath = getWorkspacePath();
      long nano = System.nanoTime();
      blames = fillBlames(workspacePath, locations, blames, logger);
      logger.logInfo("Blaming of authors took %d seconds", 1 + (System.nanoTime() - nano) / 1_000_000_000L);
      return blames;
   }

   private Blames fillBlames(String workspacePath, FileLocations locations, Blames blames, FilteredLog logger) {
      final String workspacePathSlash = workspacePath.replaceAll("\\\\", "/");
      Launcher launcher;
      try {
         launcher = workspace.createLauncher(listener);
      } catch (Exception ex) {
         logger.logException(ex, "Error creating launcher for annotating files.");
         return blames;
      }
      FileBlameBuilder builder = new FileBlameBuilder();
      for (String relativeFile : locations.getFiles()) {
         if ( relativeFile.startsWith("..")) {
            logger.logInfo("Skipping file '%s' (not in workspace path)", relativeFile);
            continue;
         }
         Set<Integer> lineSet = locations.getLines(relativeFile);
         final int lastIndex = relativeFile.lastIndexOf("/");
         if (lastIndex < 0) {
            logger.logInfo("Skipping file '%s' (not in a module)", relativeFile);
            continue;
         }
         final String fileName = relativeFile.substring(lastIndex + 1);
         final String repository = relativeFile.substring(0,lastIndex);
         logger.logInfo("Getting annotations for repo: %s, file: %s", repository, fileName);
         boolean bAlreadyBlamedCreator = false;
         try {
            Map<Integer,SurroundSCMAnnotation> annotations = sscm.annotate(environment, launcher, workspace, listener, repository, fileName);
            if (annotations.isEmpty()) {
               logger.logError("Got empty annotations for repo: %s, file: %s", repository, fileName);
            } else {
               for (int lineNr : lineSet) {
                  int key = lineNr;
                  if ( ! annotations.containsKey(key)) {
                     logger.logInfo("No annotation found for line %d, repo: %s, file: %s. Blaming creator.", lineNr, repository, fileName);
                     if ( ! bAlreadyBlamedCreator && annotations.containsKey(0)) {
                        bAlreadyBlamedCreator = true;
                        key = 0;
                     } else {
                        continue;
                     }
                  }
                  SurroundSCMAnnotation s = annotations.get(key);
                  FileBlame fileBlame = builder.build(relativeFile);
                  final String user = s.getUser();
                  fileBlame.setCommit(lineNr, String.valueOf(s.getVersion()));
                  fileBlame.setName(lineNr, user);
                  fileBlame.setEmail(lineNr, getMailForUser(launcher, user));
                  blames.add(fileBlame);
               }
            }
         } catch (IOException ioex) {
            logger.logException(ioex, "Error in annotating file '%s'.", relativeFile);
         } catch (InterruptedException intex) {
            logger.logException(intex, "Annotation of '%s' was interrupted.", relativeFile);
         }
      }
      return blames;
   }

   private String getMailForUser(Launcher launcher, String user) {
      if (userByName.containsKey(user)) {
         return userByName.get(user).getEmail();
      }
      try {
         SurroundSCMUser sscmUser = sscm.getUserInformation(environment, launcher, workspace, listener, user);
         userByName.put(user, sscmUser);
         return sscmUser.getEmail();

      } catch (NoSuchElementException nseex) {
         listener.getLogger().printf("Error finding email for user %s.%n", user);
      } catch (IOException ioex) {
         listener.getLogger().printf("Error in getting sscm information for user %s.%n", user);
      } catch (InterruptedException intex) {
         listener.getLogger().printf("Annotation was interrupted.%n");
      }
      return "<NOT_FOUND>";
   }

   private String getWorkspacePath() {
      try {
         return Paths.get(workspace.getRemote())
                     .toAbsolutePath()
                     .normalize()
                     .toRealPath(LinkOption.NOFOLLOW_LINKS)
                     .toString();
      } catch (IOException | InvalidPathException exception) {
         return workspace.getRemote();
      }
   }

}
