package andritz.sscm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;

import hudson.Launcher;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMFile;
import jenkins.scm.api.SCMProbe;
import jenkins.scm.api.SCMProbeStat;

public class SurroundSCMProbe extends SCMProbe {
   private static final long serialVersionUID = 1L;
   private final SurroundSCMHead head;
   private final SurroundSCMRevision revision;
   private transient final SurroundSCMSource scmSource;
   private final TaskListener listener;

   public SurroundSCMProbe(SurroundSCMHead head, SurroundSCMRevision revision, SurroundSCMSource scmSource, TaskListener listener) {
      this.head = head;
      this.revision = revision;
      this.scmSource = scmSource;
      this.listener = listener;
   }

   @Override public void close() throws IOException {}

   @Override
   public SCMProbeStat stat(String path) throws IOException {
      String file = path;
      String repository = head.getRepository ();
      if (path.contains("/")) {
         int idxSlash = path.lastIndexOf("/");
         file = path.substring(idxSlash + 1);
         repository = repository + "/" + path.substring(0, idxSlash);
      }

      if (scmSource == null) {
         throw new IOException("SurroundSCMProbe.scmSource is null. Unable to check file '" + path + "'.");
      }

      ArgumentListBuilder cmd = new ArgumentListBuilder();
      cmd.add("sscm");
      cmd.add("ls");
      cmd.add(file);
      cmd.add("-b".concat(head.getName()));
      cmd.add("-p".concat(repository));
      scmSource.addSscmArgServer(cmd);
      scmSource.addSscmArgUser(cmd);

      try {
         ByteArrayOutputStream baos = new ByteArrayOutputStream();
         final Node node = Jenkins.get();
         Launcher launcher = node.createLauncher(listener);
         launcher.launch().cmds(cmd).stdout(baos).join();
         String output = baos.toString("US-ASCII");
         String[] lines = output.split("\r?\n");
         boolean bRepositoryMatched = false;
         for (String line : lines) {
            // Is this a line of a repository ?
            if ( ! line.startsWith(" ")) {
               // If the repository already matched, we are done now.
               if (bRepositoryMatched) {
                  break;
               }
               // Does the repository match the searched one?
               if (line.equals(repository)) {
                  bRepositoryMatched = true;
               }
               continue;
            }
            // Here we have a file line
            if ( ! bRepositoryMatched) {
               continue;   // of another repository
            }
            String lineFile = line.trim().split(" ",2)[0];
            // Does the file match ?
            if (lineFile.equals(file)) {
               return SCMProbeStat.fromType(SCMFile.Type.REGULAR_FILE);
            }
         }
      } catch (Exception e) {
			throw new IOException("Unable to check file: " + e.getMessage());
		}
      return SCMProbeStat.fromType(SCMFile.Type.NONEXISTENT);
   }

   @Override
   public String name() {
      return head.getName();
   }

   @Override
   public long lastModified() {
      // Currently there is no way of cheaply finding this out.
      return 0L;
   }

}