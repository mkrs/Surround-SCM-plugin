package andritz.sscm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import hudson.scm.SurroundSCM;
import hudson.util.LogTaskListener;
import jenkins.scm.api.SCMFile;
import jenkins.scm.api.SCMFileSystem;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceDescriptor;

import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SurroundSCMFileSystem extends SCMFileSystem {

	private static Logger logger = Logger.getLogger(SurroundSCMFileSystem.class.getName());

   private final Path tempDir;
   private final Item owner;
   private final SurroundSCM scm;
   private final SurroundSCMRevision revision;
   private final TaskListener listener;
   private Job<?,?> job;
   private EnvVars env = new EnvVars();

   protected SurroundSCMFileSystem(@NonNull Item owner, @NonNull SurroundSCM scm, @NonNull SurroundSCMRevision rev) throws Exception {
      super(rev);
      this.tempDir = Files.createTempDirectory(String.format("sscm-%s",rev.getHead ().getName()));
      this.owner = owner;
      this.scm = scm;
      this.revision = rev;
      this.listener = new LogTaskListener(logger, Level.INFO);

      if (owner instanceof WorkflowJob) {
         WorkflowJob _job = (WorkflowJob) owner;
		   job = _job;
			Run<?,?> build = _job.getLastBuild();
			env = build.getEnvironment(listener);
		}
   }

   public Item getOwner() {
      return owner;
   }

   public SurroundSCM getScm() {
      return scm;
   }

   public SurroundSCMRevision getRevision() {
      return revision;
   }

   public TaskListener getListener() {
      return listener;
   }

   public Job<?,?> getJob() {
      return job;
   }

   public EnvVars getEnv() {
      return env;
   }

   public Path getTempDir() {
      return tempDir;
   }

   @Override
   public void close() throws IOException {
      Files.walkFileTree(tempDir, new SimpleFileVisitor<Path>() {
         @Override
         public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
         }
      });
   }

   @Override
   public long lastModified() throws IOException, InterruptedException {
      return 0;
   }

   @Override
   public SCMFile getRoot() {
      return new SurroundSCMFile(this);
   }

   @Extension
   public static class BuilderImpl extends SCMFileSystem.Builder {

      @Override
      public boolean supports(SCM source) {
         if (source instanceof SurroundSCM) {
            return true;
         }
         return false;
      }

      @Override
      protected boolean supportsDescriptor(SCMDescriptor descriptor) {
         if (descriptor instanceof SurroundSCM.SurroundSCMDescriptor) {
            return true;
         }
         return false;
      }

      @Override
      public boolean supports(SCMSource source) {
         if (source instanceof SurroundSCMSource) {
            return true;
         }
         return false;
      }

      @Override
      protected boolean supportsDescriptor(SCMSourceDescriptor descriptor) {
         if (descriptor instanceof SurroundSCMSource.DescriptorImpl) {
            return true;
         }
         return false;
      }

      @Override
      public SCMFileSystem build(@NonNull Item owner, SCM scm, @CheckForNull SCMRevision rev)
            throws IOException, InterruptedException {
         if (scm == null || !(scm instanceof SurroundSCM)) {
            return null;
         }
         SurroundSCM sscm = (SurroundSCM) scm;
         
         if (rev == null) {
            return null;
         }

         if (!(rev instanceof SurroundSCMRevision)) {
            return null;
         }
         SurroundSCMRevision revision = (SurroundSCMRevision) rev;

         try {
            return new SurroundSCMFileSystem(owner, sscm, revision);
         } catch (Exception e) {
            throw new IOException(e);
         }
      }
	}
}
