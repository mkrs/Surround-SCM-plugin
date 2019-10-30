package andritz.sscm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Launcher;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Item;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.scm.SCM;
import hudson.scm.SSCMUtils;
import hudson.scm.SurroundSCM;
import hudson.security.ACL;
import hudson.util.ArgumentListBuilder;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceDescriptor;

public class SurroundSCMSource extends SCMSource {
   private static final Logger logger = Logger.getLogger(SurroundSCMSource.class.getName());
   /*
    * all configuration fields should be private mandatory fields should be final
    * non-mandatory fields should be non-final
    */

   private final String server;

   private final String serverPort;

   private final String branch;

   private final String repository;

   @CheckForNull
   private String credentialsId;

   public String getServer() {
      return server;
   }

   public String getServerPort() {
      return serverPort;
   }

   public String getBranch() {
      return branch;
   }

   public String getRepository() {
      return repository;
   }

   public String getCredentialsId() {
      return credentialsId;
   }

   @DataBoundSetter
   public void setCredentialsId(@CheckForNull String credentialsId) {
      this.credentialsId = credentialsId;
   }

   @DataBoundConstructor
   public SurroundSCMSource(String server, String serverPort, String branch, String repository, String credentialsId) {
      this.server = server;
      this.serverPort = serverPort;
      this.branch = branch;
      this.repository = repository;
      this.credentialsId = credentialsId;
   }

   @Override
   protected void retrieve(@CheckForNull SCMSourceCriteria criteria, @NonNull SCMHeadObserver observer,
         @CheckForNull SCMHeadEvent<?> event, @NonNull TaskListener listener) throws IOException, InterruptedException {

      ArgumentListBuilder cmd = new ArgumentListBuilder();
      cmd.add("sscm");
      cmd.add("lsbranch");
      cmd.add("-b".concat(branch));
      cmd.add("-p".concat(repository));
      cmd.add("-a"); // Display all branches and their properties.
      addSscmArgServer(cmd);
      addSscmArgUser(cmd);

      PrintStream logger = listener.getLogger();
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      final Node node = Jenkins.get();
      Launcher launcher = node.createLauncher(listener);
      launcher.launch().cmds(cmd).stdout(baos).join();
      
      // Sample output of 'sscm lsbranch -a':
      // HIPASE (mainline)(active:yes)(caching:always)(frozen:yes)(hidden:no)
      // Baseline (baseline)(active:yes)(caching:on demand)(frozen:no)(hidden:no)
      // base090 (baseline)(active:yes)(caching:on demand)(frozen:yes)(hidden:yes)
      // free09001 (baseline)(active:yes)(caching:on demand)(frozen:yes)(hidden:yes)
      String output = baos.toString();
      String[] lines = output.split("\r?\n");
      String regex = "^(\\S+) "
            + "\\((\\S+)\\)" // branch type
            + "\\(active:(yes|no)\\)" // active
            + "\\(caching:([^\\)]+)\\)" // caching
            + "\\(frozen:(yes|no)\\)" // frozen
            + "\\(hidden:(yes|no)\\)" // hidden
            + "$";
      String sYes = "yes";
      Pattern pattern = Pattern.compile(regex);
      for (String line : lines) {
         Matcher m = pattern.matcher(line);
         if (!m.matches()) {
            logger.format("Regex does not match line '%s'%n", line);
            continue;
         }
         String branch = m.group(1);
         String type = m.group(2);
         boolean bActive = m.group(3).equals(sYes);
         boolean bFrozen = m.group(5).equals(sYes);
         boolean bHidden = m.group(6).equals(sYes);
         if (type == "mainline") {
            logger.format("ignoring branch '%s' because it is a mainline branch%n", branch);
            continue;
         }
         if ((!bActive) || bFrozen || bHidden) {
            logger.format("ignoring branch '%s' because bActive=%b bFrozen=%b bHidden=%b%n", branch, bActive, bFrozen, bHidden);
            continue;
         }

         SurroundSCMHead head = new SurroundSCMHead(branch,repository);
         SurroundSCMRevision revision = new SurroundSCMRevision(head);
         // null criteria means that all branches match.
         if (criteria == null) {
            // get revision and add observe
            observer.observe(head, revision);
         } else {
            SCMSourceCriteria.Probe probe = new SurroundSCMProbe(head,revision,this,listener);
            if (criteria.isHead(probe, listener)) {
               logger.format("observe branch: '%s'%n", head.getName());
               observer.observe(head, revision);
            } else {
               logger.format("ignoring branch '%s' because criteria say it is not a head.%n", head.getName());
            }
         }
         // check for user abort
         checkInterrupt();
      }
   }

   @Override
   public SCM build(SCMHead head, SCMRevision revision) {
      String argBranch = "";
      if (head instanceof SurroundSCMHead) {
         SurroundSCMHead sscmHead = (SurroundSCMHead) head;
         argBranch = sscmHead.getName();
      }
      SurroundSCM sscm = new SurroundSCM(server, serverPort, argBranch, repository, credentialsId);
      return sscm;
   }

   public void addSscmArgServer(ArgumentListBuilder cmd) {
      cmd.add(String.format("-z%s:%s",server,serverPort));
   }

   public void addSscmArgUser(ArgumentListBuilder cmd) {
      StandardUsernameCredentials credentials = getCredentials();
      if (credentials != null && credentials instanceof UsernamePasswordCredentials) {
         UsernamePasswordCredentials upc = (UsernamePasswordCredentials) credentials;
         String result = String.format("-y%s:%s", upc.getUsername(), upc.getPassword().getPlainText());
         cmd.addMasked(result);
      }
   }

   @CheckForNull
   protected StandardUsernameCredentials getCredentials() {
      return getCredentials(getOwner());
   }

   @CheckForNull
   private StandardUsernameCredentials getCredentials(@CheckForNull Item context) {
      String credentialsId = getCredentialsId();
      if (credentialsId == null) {
         return null;
      }
      return CredentialsMatchers.firstOrNull(
            CredentialsProvider.lookupCredentials(StandardUsernameCredentials.class, context, ACL.SYSTEM,
                  URIRequirementBuilder.fromUri(credentialsId).build()),
            CredentialsMatchers.allOf(CredentialsMatchers.withId(credentialsId)));
   }

   @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED)
   public static void onLoaded() {
      // creates default tool installation if needed. Uses "sscm" or migrates data
      // from previous versions.

      DescriptorImpl descriptor = (DescriptorImpl) Jenkins.get().getDescriptor(SurroundSCMSource.class);
      if (descriptor == null) {
         logger.severe("Jenkins has no registered Descriptor for SurroundSCMSource.");
      }
   }
   
   @Extension
	public static class DescriptorImpl extends SCMSourceDescriptor {

		@Override
		public String getDisplayName() {
			return "Surround SCM";
      }
      
      public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item context, @QueryParameter String remote) {
         return SSCMUtils.doFillCredentialsIdItems(context, remote);
      }

	}
}