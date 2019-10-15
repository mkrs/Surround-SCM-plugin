package andritz.sscm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.Item;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.scm.SCM;
import hudson.scm.SurroundSCM;
import hudson.security.ACL;
import hudson.util.ArgumentListBuilder;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;

public final class SurroundSCMSource extends SCMSource {
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
      cmd.add(String.format("-z%s:%s", server, serverPort));
      cmd.addMasked(getSscmArgUser());

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
      String regex = "^(\\s+) " + "\\((\\s+)\\)" // branch type
            + "\\(active:(yes|no)\\)" // active
            + "\\(caching:([^\\)]+)\\)" // caching
            + "\\(frozen:(yes|no)\\)" // frozen
            + "\\(hidden:(yes|no)\\)" // hidden
            + "$";
      Pattern pattern = Pattern.compile(regex);
      for (String line : lines) {
         Matcher m = pattern.matcher(line);
         if (!m.matches()) {
            logger.format("Regex does not match line '%s'", line);
            continue;
         }
         String branch = m.group(1);
         String type = m.group(2);
         boolean bActive = m.group(3) == "yes";
         boolean bFrozen = m.group(5) == "yes";
         if (type == "mainline") {
            continue;
         }
         if (!bActive || bFrozen) {
            logger.format("ignoring branch '%s' because bActive=%b bFrozen=%b", branch, bActive, bFrozen);
            continue;
         }
         SurroundSCMHead head = new SurroundSCMHead(branch);
         SurroundSCMRevision revision = new SurroundSCMRevision(head);
         observer.observe(head, revision);
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

   private String getSscmArgUser() {
      String result = "";
      StandardUsernameCredentials credentials = getCredentials();
      if (credentials != null && credentials instanceof UsernamePasswordCredentials) {
         UsernamePasswordCredentials upc = (UsernamePasswordCredentials) credentials;
         result = String.format("-y%s:%s", upc.getUsername(), upc.getPassword().getPlainText());
      }
      return result;
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
   
}