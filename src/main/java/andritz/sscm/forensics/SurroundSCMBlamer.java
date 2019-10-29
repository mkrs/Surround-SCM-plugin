package andritz.sscm.forensics;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.PatternSyntaxException;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.SurroundSCM;
import hudson.scm.SurroundSCMAnnotation;
import hudson.scm.SurroundSCMUser;
import io.jenkins.plugins.forensics.blame.Blamer;
import io.jenkins.plugins.forensics.blame.Blames;
import io.jenkins.plugins.forensics.blame.FileBlame;
import io.jenkins.plugins.forensics.blame.FileLocations;
import jenkins.model.Jenkins;

public class SurroundSCMBlamer extends Blamer {
   private static final long serialVersionUID = 3013015086648085760L;

   private final SurroundSCM sscm;
   private final Run<?, ?> build;
   private final FilePath workspace;
   private final TaskListener listener;
   private final Map<String,SurroundSCMUser> userByName;

   public SurroundSCMBlamer(SurroundSCM sscm, final Run<?, ?> build, final FilePath workspace, final TaskListener listener) {
      this.sscm = sscm;
      this.build = build;
      this.workspace = workspace;
      this.listener = listener;
      this.userByName = new HashMap<>();
   }

   @Override
   public Blames blame(FileLocations locations) {
      Blames blames = new Blames();
      if (locations.isEmpty()) {
         return blames;
      }

      blames.logInfo("Invoking SurroundSCM blamer to create author and version information for %d affected files", locations.size());

      String workspacePath = getWorkspacePath();
      blames.logInfo("Job workspace = '%s'", workspacePath);

      long nano = System.nanoTime();
      blames = fillBlames(workspacePath, locations, blames);
      blames.logInfo("Blaming of authors took %d seconds", 1 + (System.nanoTime() - nano) / 1_000_000_000L);
      return blames;
   }

   private Blames fillBlames(String workspacePath, FileLocations locations, Blames blames) {
      final String branch = sscm.getBranch();
      final Node node = Jenkins.get();
      Launcher launcher = node.createLauncher(listener);
      try {
         for (String file : locations.getFiles()) {
            if ( ! file.startsWith(workspacePath)) {
               blames.logError("Skipping file '%s' (not in workspace path)", file);
               continue;
            }
            String relativeFile = file.substring(workspacePath.length());
            final int lastIndex = relativeFile.lastIndexOf("/");
            final String fileName = relativeFile.substring(lastIndex + 1);
            final String repository = relativeFile.substring(0,lastIndex);
            blames.logInfo("Getting annotations for repo: %s, file: %s", repository, fileName);
            List<SurroundSCMAnnotation> annotations = sscm.annotate(build, launcher, workspace, listener, repository, fileName);
            for (SurroundSCMAnnotation s : annotations) {
               FileBlame fileBlame = new FileBlame(file);
               final int lineNr = s.getLine();
               final String user = s.getUser();
               fileBlame.setCommit(lineNr, String.valueOf(s.getVersion()));
               fileBlame.setName(lineNr, user);
               fileBlame.setEmail(lineNr, getMailForUser(launcher, user));
               blames.add(fileBlame);
            }
         }
      } catch (PatternSyntaxException psex) {
         blames.logException(psex, "Error creating pattern for branch %s", branch);
      } catch (IOException ioex) {
         blames.logException(ioex, "Error in annotating file.");
      } catch (InterruptedException intex) {
         blames.logException(intex, "Annotation was interrupted.");
      }
      return blames;
   }

   private String getMailForUser(Launcher launcher, String user) {
      if (userByName.containsKey(user)) {
         return userByName.get(user).getEmail();
      }
      try {
         SurroundSCMUser sscmUser = sscm.getUserInformation(build, launcher, workspace, listener, user);
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
