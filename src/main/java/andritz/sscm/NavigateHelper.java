package andritz.sscm;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.scm.SurroundSCM;
import hudson.util.ArgumentListBuilder;
import jenkins.model.Jenkins;

public class NavigateHelper {

   private static final Logger logger = Logger.getLogger(NavigateHelper.class.getName());

   private final SurroundSCMFileSystem fs;
   private final SurroundSCM scm;
   private final SurroundSCMHead head;
   private final TaskListener listener;
	private List<Node> nodes;

   public NavigateHelper(@NonNull SurroundSCMFileSystem fs) {
      this.fs = fs;
      this.scm = fs.getScm();
      this.head = (SurroundSCMHead)fs.getRevision().getHead();
      this.listener = fs.getListener();
   }

   /**
	 * Get a list of path nodes.
	 *
	 * @param localPath a relative local path e.g. "" for root or "projA/comX"
	 * @return list of nodes
	 */
	public List<Node> getNodes(String localPath) {
		nodes = new ArrayList<>();

		String path = head.getRepository() + "/" + localPath;
		if (!path.isEmpty() && !path.endsWith("/")) {
			path = path + "/";
		}
		buildPaths(path);

		return nodes;
	}

	private void buildPaths(String localPath) {
      String repository = head.getRepository ();
      String file = localPath;
      if (localPath.contains("/")) {
         int idxSlash = localPath.lastIndexOf("/");
         file = localPath.substring(idxSlash + 1);
         repository = repository + "/" + localPath.substring(0, idxSlash);
      }

      ArgumentListBuilder cmd = new ArgumentListBuilder();
      cmd.add("sscm");
      cmd.add("ls");
      cmd.add(file);
      cmd.add("-b".concat(head.getName()));
      cmd.add("-p".concat(repository));
      scm.addSscmArgServer(cmd);
      scm.addSscmArgUser(cmd,fs.getJob());

      try {
         ByteArrayOutputStream baos = new ByteArrayOutputStream();
         final hudson.model.Node node = Jenkins.get();
         Launcher launcher = node.createLauncher(listener);
         launcher.launch().cmds(cmd).stdout(baos).join();
         String output = baos.toString();
         String[] lines = output.split("\r?\n");
         boolean bInSearchedRepository = false;
         for (String line : lines) {
            if (line.startsWith("  ")) {
               continue;   // Not a repository or file line
            }
            
            // Is this a line of a repository ?
            if (line.startsWith(repository)) {
               // Does the repository match the searched one?
               if (line.equals(repository)) {
                  bInSearchedRepository = true;
                  continue;
               }
               bInSearchedRepository = false;
               nodes.add(new Node(repository,true));
               continue;
            }

            // Here we have a file line
            if ( ! bInSearchedRepository) {
               continue;   // of another repository
            }
            String fileName = line.trim().split(" ",2)[0];
            nodes.add(new Node(repository + "/" + fileName,false));
         }
      } catch (Exception e) {
			listener.getLogger().println(String.format("Caught exception when building paths: %s",e));
      }
   }
   
   public InputStream getFileContent(String repositoryRelPath) throws IOException, InterruptedException {
      String tempDir = fs.getTempDir().toAbsolutePath().toString();
      if ( ! tempDir.endsWith("/")) {
         tempDir = tempDir.concat("/");
      }
      String repository = head.getRepository();
      String file = repositoryRelPath;
      int idxSlash = repositoryRelPath.lastIndexOf("/");
      file = repositoryRelPath.substring(idxSlash + 1);
      if (idxSlash != -1) {
         repository = repository + "/" + repositoryRelPath.substring(0, idxSlash);
      }

      ArgumentListBuilder cmd = new ArgumentListBuilder();
      cmd.add("sscm");
      cmd.add("get");
      cmd.add(file);
      cmd.add("-d".concat(tempDir + repository));
      cmd.add("-b".concat(head.getName()));
      cmd.add("-p".concat(repository));
      scm.addSscmArgServer(cmd);
      scm.addSscmArgUser(cmd,fs.getJob());

      try {
         final hudson.model.Node node = Jenkins.get();
         Launcher launcher = node.createLauncher(listener);
         launcher.launch().cmds(cmd).join();
         File tempFile = new File(tempDir + repository + "/" + file);
         return new DeleteOnCloseFileInputStream(tempFile);
      } catch (IOException e) {
			throw new FileNotFoundException(e.getMessage());
      }
   }

   public static final class Node {

		private String name;
		private String repository;
		private boolean isDir;

		protected Node(String repository, boolean isDir) {
         this.isDir = isDir;
         this.repository = repository;
         
         int idxSlash = repository.lastIndexOf("/");
         this.name = repository.substring(idxSlash + 1);
         
         if (isDir) {
            if(repository.contains("/")) {
               this.repository = repository.substring(0,idxSlash);
            } else {
               this.repository = "";
            }
         }
		}

		public String getName() {
			return name;
		}

		public String getRepository() {
			return repository;
		}

		public boolean isDir() {
			return isDir;
		}
	}
}