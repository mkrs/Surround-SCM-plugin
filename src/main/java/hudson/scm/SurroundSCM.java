package hudson.scm;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;

import org.jenkinsci.plugins.plaincredentials.FileCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.config.RSAKey;
import hudson.util.ArgumentListBuilder;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

@Extension
public final class SurroundSCM extends SCM implements Serializable {
    private static final long serialVersionUID = 7899036674317233131L;

    /**
     * Singleton descriptor.
     */
    // @SuppressWarnings("WeakerAccess")
    // @Extension
    // public static final SurroundSCMDescriptor DESCRIPTOR = new
    // SurroundSCMDescriptor();

    /**
     * We consider any changes 'significant'
     */
    private static transient final int changesThreshold = 1;

    /**
     * Used for confirming plugin version from debug logs from customers
     */
    private static transient final int pluginVersion = 9;

    /**
     * Internal constant used for formatting datetime fields for the Surround SCM
     * CLI
     */
    private static transient final String SURROUND_DATETIME_FORMAT_STR = "yyyyMMddHHmmss";
    /**
     * Internal constant used for formatting datetime fields for the Surround SCM
     * CLI
     */
    private static transient final String SURROUND_DATETIME_FORMAT_STR_2 = "yyyyMMddHH:mm:ss";

    // config options
    private String server;
    private String serverPort;
    private String branch;
    private String repository;
    private String credentialsId;
    private RSAKey rsaKey;
    private UsernamePasswordCredentials blameCredentials;
    private String blameExe;
    private transient String sscmLsuserStdout;

    // TODO: Review if this is needed.
    private String sscm_tool_name;

    /**
     * Leaving this here for future functionality.
     */
    private boolean bIncludeOutput;

    /**
     * @deprecated This was used to store the absolute path to the Surround SCM RSA
     *             Key file. We now use {@link RSAKey} to store this information.
     */
    @SuppressWarnings({ "FieldCanBeLocal", "unused", "DeprecatedIsStillUsed" })
    private transient String rsaKeyPath;

    /**
     * @deprecated This was used to store the local path to the Surround SCM
     *             Executable. It is no longer needed since we moved to using the
     *             SurroundTool.
     */
    @SuppressWarnings({ "unused", "DeprecatedIsStillUsed" })
    // Needs to stay to allow Jenkins to upgrade old plugin installations
    private transient String surroundSCMExecutable;

    /**
     * @deprecated This was used to store the username used to connect to Surround.
     *             For legacy support reasons we are leaving the variable here so
     *             people can have a smooth upgrade. However if they edit their
     *             project they will be forced to use the new Credentials interface.
     */
    @SuppressWarnings("DeprecatedIsStillUsed")
    private String userName;

    /**
     * @deprecated This was used to store the password used to connect to Surround.
     *             For legacy support reasons we are leaving the variable here so
     *             people can have a smooth upgrade. However if they edit their
     *             project they will be forced to use the new Credentials interface.
     */
    @SuppressWarnings("DeprecatedIsStillUsed")
    private String password;

    @SuppressWarnings("deprecation")
    @DataBoundConstructor
    public SurroundSCM(String server, String serverPort, String branch, String repository, String credentialsId) {
        this.rsaKeyPath = null;
        this.rsaKey = null;

        this.server = Util.fixEmptyAndTrim(server);
        this.serverPort = Util.fixEmptyAndTrim(serverPort);
        this.branch = Util.fixEmptyAndTrim(branch);
        this.repository = Util.fixEmptyAndTrim(repository);
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);

        this.bIncludeOutput = true; // Leaving this here for future functionality.

        this.userName = null;
        this.password = null;
        this.surroundSCMExecutable = null;
    }

    /*
     * - Switched to @DataBoundSetter's for optional parameters - Switched to
     * Credential storage for usernames & rsakeys - Updated the config page, which
     * necessitated data structure changes.
     */
    /**
     * @param rsaKeyPath            Path to the RSA key file on the remote node.
     * @param server                servername
     * @param serverPort            serverPort
     * @param userName              userName
     * @param password              password
     * @param branch                branch
     * @param repository            repository
     * @param surroundSCMExecutable path to sscm
     * @param includeOutput         includeOutput
     * @deprecated as of release v10, Significant updates to the Jenkins
     *             integration, including:
     */
    @SuppressWarnings({ "WeakerAccess", "deprecation" }) // Legacy constructor, can't make it private
    public SurroundSCM(String rsaKeyPath, String server, String serverPort, String userName, String password,
            String branch, String repository, String surroundSCMExecutable, boolean includeOutput) {
        this(server, serverPort, branch, repository, null);
        this.rsaKey = new RSAKey(RSAKey.Type.Path, rsaKeyPath);
        this.userName = Util.fixEmptyAndTrim(userName);
        this.password = Util.fixEmptyAndTrim(password);
        this.surroundSCMExecutable = Util.fixEmptyAndTrim(surroundSCMExecutable);
        this.bIncludeOutput = includeOutput;
    }

    /**
     * @param rsaKeyPath            Path to the RSA key file on the remote node.
     * @param server                servername
     * @param serverPort            serverPort
     * @param userName              userName
     * @param password              password
     * @param branch                branch
     * @param repository            repository
     * @param surroundSCMExecutable path to sscm
     * @deprecated Deprecated as of release v9, added option to include // exclude
     *             output.
     */
    @SuppressWarnings({ "deprecation", "unused" })
    public SurroundSCM(String rsaKeyPath, String server, String serverPort, String userName, String password,
            String branch, String repository, String surroundSCMExecutable) {
        this(rsaKeyPath, server, serverPort, userName, password, branch, repository, surroundSCMExecutable, true);
    }

    @SuppressWarnings("unused")
    @Deprecated
    public SurroundSCM() {
    }

    /**
     * @return Returns the login username, this has been deprecated in favor of the
     *         credentials plugin
     */
    @SuppressWarnings({ "unused", "deprecation" })
    public String getUserName() {
        return userName;
    }

    /**
     * @return Returns the old password.
     */
    @SuppressWarnings({ "unused", "deprecation" })
    public String getPassword() {
        return password;
    }

    /**
     * Used to populate the rsaKeyFilePath field in stapler.
     *
     * @return If using an RSA key file path, returns the path, otherwise returns
     *         null.
     */
    @SuppressWarnings("unused") // Called from stapler to setup the 'rsaKeyFilePath' field on config.jelly
    public String getRsaKeyFilePath() {
        String result = null;
        if (rsaKey != null && rsaKey.getRsaKeyType() == RSAKey.Type.Path) {
            result = rsaKey.getRsaKeyValue();
        } else if (rsaKeyPath != null && !rsaKeyPath.isEmpty()) {
            result = rsaKeyPath;
        }
        return result;
    }

    @DataBoundSetter
    public void setRsaKeyFilePath(String rsaKeyFilePath) {
        this.rsaKey = new RSAKey(RSAKey.Type.Path, rsaKeyFilePath);
    }

    /**
     * Used to populate the rsaKeyFileId field in stapler.
     *
     * @return If using an RSA key file ID, returns the ID string, otherwise returns
     *         null.
     */
    @SuppressWarnings("unused") // Called from stapler to setup the 'rsaKeyFileId' field on config.jelly
    public String getRsaKeyFileId() {
        String result = null;
        if (rsaKey != null && rsaKey.getRsaKeyType() == RSAKey.Type.ID) {
            result = rsaKey.getRsaKeyValue();
        }
        return result;
    }

    @DataBoundSetter
    public void setRsaKeyFileId(String rsaKeyFileId) {
        this.rsaKey = new RSAKey(RSAKey.Type.ID, rsaKeyFileId);
    }

    @Exported
    public String getServer() {
        return server;
    }

    @Exported
    public String getServerPort() {
        return serverPort;
    }

    @Exported
    public String getBranch() {
        return branch;
    }

    @Exported
    public String getRepository() {
        return repository;
    }

    @Exported
    public boolean getIncludeOutput() {
        return bIncludeOutput;
    }

    // TODO: @DataBoundSetter
    public void setIncludeOutput(boolean includeOutput) {
        this.bIncludeOutput = includeOutput;
    }

    @SuppressWarnings("WeakerAccess") // Access needed for Stapler
    public String getCredentialsId() {
        return credentialsId;
    }

    @Exported
    public boolean hasRsaKeyConfigured() {
        return rsaKey == null || rsaKey.getRsaKeyType() != RSAKey.Type.NoKey;
    }

    @Exported
    public boolean isUsingRsaKeyPath() {
        // This function is a bit screwy due to needing to handle legacy plugin
        // configurations. In old configurations
        // we didn't have a plain 'rsaKey' variable, but instead had 'rsaKeyPath'. We
        // need to select the 'Path'
        // in two situations:
        // 1. rsaKey exists & is a Path type.
        // 2. rsaKeyPath exists & is not blank.
        boolean result = rsaKeyPath != null && !rsaKeyPath.isEmpty();
        return result || (rsaKey != null && rsaKey.getRsaKeyType() == RSAKey.Type.Path);
    }

    @Exported
    public boolean isUsingRsaKeyFileId() {
        return rsaKey != null && rsaKey.getRsaKeyType() == RSAKey.Type.ID;
    }

    /**
     * This function was required to make the Snippet Generator work
     *
     * @return Always returns null because we want users to use rsaKeyFileId: and
     *         rsaKeyFilePath:, not rsaKey: [type: "ID" value: "blah"]. When we
     *         actually returned the rsaKey here, it screwed w/ the the Snippet
     *         Generator.
     */
    @Exported
    public RSAKey getRsaKey() {
        return null;
    }

    @DataBoundSetter
    public void setRsaKey(RSAKey rsaKey) {
        this.rsaKey = rsaKey;
    }

    /**
     * @return Returns 'null' to indicate that this is an un-used field.
     * @deprecated This was getting called.... not entirely sure why, having it
     *             always return 'null' to indicate that the field is not being
     *             used. It was showing up in the 'Snippet Generator' for the
     *             'checkout' command prior to me having this return null.
     *             <p>
     *             When I didn't have this field, and it was not marked
     *             as @{@link Exported} the 'Snippet Generator' was throwing errors
     *             and wouldn't correctly generate the example 'checkout' command.
     */
    @Exported
    public String getRsaKeyPath() {
        return null;
    }

    /**
     * @param rsaKeyPath Path to the RSA key file on the remote node.
     * @deprecated Leaving this here for when reading in old versions of the plugin
     *             we can setup the new version of the rsaKey storage.
     */
    @DataBoundSetter
    public void setRsaKeyPath(String rsaKeyPath) {
        setRsaKeyFilePath(rsaKeyPath);
    }

    // @Override
    // public SCMDescriptor<?> getDescriptor() {
    // return DESCRIPTOR;
    // }

    /**
     * Calculates the SCMRevisionState that represents the state of the workspace of
     * the given build. The returned object is then fed into the
     * compareRemoteRevisionWith(AbstractProject, Launcher, FilePath, TaskListener,
     * SCMRevisionState) method as the baseline SCMRevisionState to determine if the
     * build is necessary.
     * <p>
     * {@inheritDoc}
     */
    @Override
    public SCMRevisionState calcRevisionsFromBuild(@Nonnull Run<?, ?> build, @Nullable FilePath workspace,
            @Nullable Launcher launcher, @Nonnull TaskListener listener) throws IOException, InterruptedException {
        SimpleDateFormat scm_datetime_formatter = new SimpleDateFormat(SURROUND_DATETIME_FORMAT_STR);

        final Date lastBuildDate = build.getTime();
        final int lastBuildNum = build.getNumber();
        SurroundSCMRevisionState scmRevisionState = new SurroundSCMRevisionState(lastBuildDate, lastBuildNum);
        listener.getLogger()
                .println("calcRevisionsFromBuild determined revision for build #" + scmRevisionState.getBuildNumber()
                        + " built originally at " + scm_datetime_formatter.format(scmRevisionState.getDate())
                        + " pluginVer: " + pluginVersion);

        return scmRevisionState;
    }

    /**
     * We don't actually NEED a workspace for polling. We are saving our info to a
     * system temp file, further the way we are currently working (saving the info
     * to a system temp file) is a bit daft since we could just directly read the
     * command output rather than saving it to a file.
     * <p>
     * However, we have relied on this to be set to 'True' since we started coding,
     * and when I tried to turn it off, stuff broke. Maybe we can try again later?
     *
     * @return Returns 'True' {@inheritDoc}
     */
    @Override
    public boolean requiresWorkspaceForPolling() {
        return true;
    }

    @Override
    public boolean supportsPolling() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PollingResult compareRemoteRevisionWith(@Nonnull Job<?, ?> project, @Nullable Launcher launcher,
            @Nullable FilePath workspace, @Nonnull TaskListener listener, @Nonnull SCMRevisionState baseline)
            throws IOException, InterruptedException {
        SimpleDateFormat scm_datetime_formatter = new SimpleDateFormat(SURROUND_DATETIME_FORMAT_STR);

        Date lastBuild = ((SurroundSCMRevisionState) baseline).getDate();
        int lastBuildNum = ((SurroundSCMRevisionState) baseline).getBuildNumber();

        Date now = new Date();
        File temporaryFile = File.createTempFile("changes", "txt");

        listener.getLogger().println("Calculating changes since build #" + lastBuildNum + " which happened at "
                + scm_datetime_formatter.format(lastBuild) + " pluginVer: " + pluginVersion);

        double countChanges = 0;
        if (launcher != null)
            countChanges = determineChangeCount(project, launcher, listener, lastBuild, now, temporaryFile, workspace);
        else
            listener.getLogger().println("Launcher was null... skipping determining change count.");

        if (!temporaryFile.delete()) {
            listener.getLogger().println("Failed to delete temporary file [" + temporaryFile.getAbsolutePath()
                    + "] marking the file to be deleted when Jenkins restarts.");
            temporaryFile.deleteOnExit();
        }

        if (countChanges == 0)
            return PollingResult.NO_CHANGES;
        else if (countChanges < changesThreshold)
            return PollingResult.SIGNIFICANT;

        return PollingResult.BUILD_NOW;
    }

    /**
     * Obtains a fresh workspace of the module(s) into the specified directory of
     * the specified machine. We'll use sscm get.
     * <p>
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    // Casting the 'build' to an 'AbstractBuild' causes some warnings, however we
    // are checking to make sure build is an 'instanceof' AbstractBuild, so this
    // shouldn't be a problem.
    @Override
    public void checkout(@Nonnull Run<?, ?> build, @Nonnull Launcher launcher, @Nonnull FilePath workspace,
            @Nonnull TaskListener listener, @CheckForNull File changelogFile, @CheckForNull SCMRevisionState baseline)
            throws IOException, InterruptedException {
        SimpleDateFormat scm_datetime_formatter = new SimpleDateFormat(SURROUND_DATETIME_FORMAT_STR_2);

        Date currentDate = new Date(); // defaults to current

        EnvVars environment = build.getEnvironment(listener);
        if (build instanceof AbstractBuild) {
            EnvVarsUtils.overrideAll(environment, ((AbstractBuild) build).getBuildVariables());
        }

        ArgumentListBuilder cmd = new ArgumentListBuilder();
        cmd.add(getSscmExe(workspace, listener, environment));// will default to sscm user can put in path
        cmd.add("get");
        cmd.add("/");
        cmd.add("-wreplace");
        cmd.add("-b".concat(branch));
        cmd.add("-p".concat(repository));
        cmd.add("-d".concat(workspace.getRemote()));
        cmd.add("-r");
        cmd.add("-a1"); // for now hardcoded: get latest version in state <No State>
        // cmd.add("-s" + scm_datetime_formatter.format(currentDate));
        if (!bIncludeOutput) {
            cmd.add("-q");
        }
        cmd.add(getServerConnectionArgument(build.getParent(), environment, workspace));
        cmd.addMasked(getUserPasswordArgument(build.getParent(), environment));

        int cmdResult = launcher.launch().envs(environment).cmds(cmd).stdout(listener.getLogger()).join();
        if (cmdResult == 0) {
            Date lastBuildDate = new Date();
            lastBuildDate.setTime(0); // default to January 1, 1970

            if (baseline instanceof SurroundSCMRevisionState) {
                lastBuildDate = ((SurroundSCMRevisionState) baseline).getDate();
            } else
                listener.getLogger().print("No previous build information detected.");

            // Setup the revision state based on what we KNOW to be correct information.
            SurroundSCMRevisionState scmRevisionState = new SurroundSCMRevisionState(currentDate, build.number);
            build.addAction(scmRevisionState);
            listener.getLogger()
                    .println("Checkout calculated ScmRevisionState for build #" + build.number + " to be the datetime "
                            + scm_datetime_formatter.format(currentDate) + " pluginVer: " + pluginVersion);

            if (changelogFile != null)
                captureChangeLog(build, launcher, workspace, listener, lastBuildDate, currentDate, changelogFile,
                        environment);
        }

        listener.getLogger().println("Checkout completed.");
    }

    public void saveCredentialsAndExeForBlames(@Nonnull Run<?, ?> build, @Nonnull EnvVars environment,
            @Nonnull FilePath workspace, @Nonnull TaskListener listener) {
        StandardUsernameCredentials credentials = getCredentials(build.getParent(), environment);
        if (credentials != null && credentials instanceof UsernamePasswordCredentials) {
            blameCredentials = (UsernamePasswordCredentials) credentials;
        }
        try {
            blameExe = getSscmExe(workspace, listener, environment);
        } catch (Exception e) {
            blameExe = "sscm";
        }
    }

    public Map<Integer, SurroundSCMAnnotation> annotate(@Nonnull EnvVars environment, @Nonnull Launcher launcher,
            @Nonnull FilePath workspace, @Nonnull TaskListener listener,
            String repo, String file) throws IOException, InterruptedException {
        Map<Integer, SurroundSCMAnnotation> annotations = new HashMap<>();

        ArgumentListBuilder cmd = new ArgumentListBuilder();
        cmd.add(blameExe);
        cmd.add("annotate");
        cmd.add(String.format("%s/%s", repo, file));
        cmd.add(getServerConnectionArgument(null, environment, workspace));
        cmd.addMasked(getUserPasswordArgument(blameCredentials));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int cmdResult = launcher.launch().envs(environment).cmds(cmd).pwd(workspace)
            .quiet(true).stdout(baos).stderr(new OutputStream() { @Override public void write(int b) { } }).join(); // ignore stderr
        if (cmdResult == 0) {
            Pattern annotateLinePattern = Pattern.compile("(\\S+)\\s+(\\d+)\\s*");
            String content = baos.toString("US-ASCII");
            List<String> lines = Arrays.stream(content.split("\r?\n")).skip(2).collect(Collectors.toList());
            int lineNr = 0;
            for (String line : lines) {
                lineNr++;
                Matcher m = annotateLinePattern.matcher(line);
                if (m.lookingAt()) {
                    String user = checkUserName(m.group(1));
                    annotations.put(lineNr,new SurroundSCMAnnotation(lineNr, user, Integer.parseInt(m.group(2))));
                }
            }
            listener.getLogger().printf("Annotate of %s/%s completed with %d lines and %d annotations.%n", repo, file, lineNr, annotations.size());
        } else {
            cmd.clear();
            cmd.add(blameExe);
            cmd.add("history");
            cmd.add(String.format("%s/%s", repo, file));
            cmd.add("-v1:1");   // only for Version 1
            cmd.add("-aAddToRepository");   // only the add action
            cmd.add("-w-");     // no workflow actions
            cmd.add("-c-");     // no custom field actions
            cmd.add(getServerConnectionArgument(null, environment, workspace));
            cmd.addMasked(getUserPasswordArgument(blameCredentials));
            baos.reset();
            launcher.launch().envs(environment).cmds(cmd).pwd(workspace)
                .quiet(true).stdout(baos).stderr(new OutputStream() { @Override public void write(int b) { } }).join(); // ignore stderr
            String content = baos.toString("US-ASCII");
            String[] lines = content.split("\r?\n");
            boolean bMatch = false;
            Pattern addActionPattern = Pattern.compile("add\\s+(\\S+)\\s+");
            String creator = "";
            for (String line : lines) {
                if (line.startsWith("Action:")) {
                    bMatch = true;
                    continue;
                }
                if (bMatch) {
                    Matcher m = addActionPattern.matcher(line);
                    if (m.lookingAt()) {
                        creator = checkUserName(m.group(1));
                        annotations.put(0, new SurroundSCMAnnotation(0, creator, 1));
                        break;
                    }
                }
            }
            if (annotations.isEmpty()) {
                listener.getLogger().printf("Could not get creator of %s/%s.%n", repo, file);
            } else {
                listener.getLogger().printf("Annotate of %s/%s failed, blaming all on creator %s.%n", repo, file, creator);
            }
        }

        return annotations;
    }

    private String checkUserName(@Nonnull String user) {
        if (user.equals("win")) {
            return "jw";
        }
        return user;
    }

    public SurroundSCMUser getUserInformation(@Nonnull EnvVars environment, @Nonnull Launcher launcher, @Nonnull FilePath workspace, @Nonnull TaskListener listener,
        String user) throws IOException, InterruptedException, NoSuchElementException {

        if (sscmLsuserStdout == null) {
            ArgumentListBuilder cmd = new ArgumentListBuilder();
            cmd.add(blameExe);
            cmd.add("lsuser");
            cmd.add("-e");  // include email
            cmd.add("-f");  // include full name
            cmd.add(getServerConnectionArgument(null, environment, workspace));
            cmd.addMasked(getUserPasswordArgument(blameCredentials));
    
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int cmdResult = launcher.launch().envs(environment).cmds(cmd).pwd(workspace).quiet(true).stdout(baos).join();
            if (cmdResult != 0) {
                throw new IOException("Error getting user information from sscm.");
            }
            sscmLsuserStdout = baos.toString("US-ASCII");
        }
        
        Pattern fullNamePattern = Pattern.compile("^ Full name:\\s+(.*)");
        Pattern emailPattern = Pattern.compile("^ Email address:\\s+(.*)");
        String[] lines = sscmLsuserStdout.split("\r?\n");
        boolean bMatch = false;
        String fullName = "";
        String email = "";
        for (String line : lines) {
            if (line.startsWith("User name:")) {
                bMatch = false;
                if (line.endsWith(user)) {
                    bMatch = true;
                    continue;
                }
            }
            if ( ! bMatch) {
                continue;
            }
            Matcher m = fullNamePattern.matcher(line);
            if (m.matches()) {
                fullName = m.group(1);
                continue;
            }
            m = emailPattern.matcher(line);
            if (m.matches()) {
                email = m.group(1);
                break;
            }
        }
        if (bMatch) {
            return new SurroundSCMUser(user,fullName,email);
        }
        throw new NoSuchElementException(String.format("Username %s not found",user));
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public String getKey() {
        // Key=sscm://Server:Port//Branch//Repository
        String unsafeString = String.format("sscm://%s:%s//%s//%s", getServer(), getServerPort(), getBranch(), getRepository());
        return Util.getDigestOf(unsafeString);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ChangeLogParser createChangeLogParser() {
        return new SurroundSCMChangeLogParser();
    }

    /**
     * Runs the Surround SCM CLI's "CruiseControl" command to determine files that have changed since the
     * last build.
     *
     * @param build         The current build we are capturing a change log for
     * @param launcher      Launcher to use for running commands
     * @param workspace     Workspace to save the changelog too
     * @param listener      Listener used for logging
     * @param lastBuildDate The last build's date time
     * @param currentDate   The current build's date time
     * @param changelogFile File to save the changelog too
     * @param env           Environment variables to use
     * @return Returns 'True' if we successfully captured the changelog
     * @throws IOException          Access to the files can cause an IOException
     * @throws InterruptedException Launcher can throw this when running the process
     */
    private boolean captureChangeLog(@Nonnull Run<?, ?> build, Launcher launcher, FilePath workspace,
                                     TaskListener listener, Date lastBuildDate, Date currentDate, File changelogFile,
                                     EnvVars env) throws IOException, InterruptedException {

        boolean result = true;

        SimpleDateFormat scm_datetime_formatter = new SimpleDateFormat(SURROUND_DATETIME_FORMAT_STR);

        String dateRange = scm_datetime_formatter.format(lastBuildDate);
        dateRange = dateRange.concat(":");
        dateRange = dateRange.concat(scm_datetime_formatter.format(currentDate));

        ArgumentListBuilder cmd = new ArgumentListBuilder();
        cmd.add(getSscmExe(workspace, listener, env));//will default to sscm user can put in path
        cmd.add("cc");
        cmd.add("/");
        cmd.add("-d".concat(dateRange));
        cmd.add("-b".concat(branch));
        cmd.add("-p".concat(repository));
        cmd.add("-r");

        cmd.add(getServerConnectionArgument(build.getParent(), env, workspace));
        cmd.addMasked(getUserPasswordArgument(build.getParent(), env));

        FileOutputStream os = new FileOutputStream(changelogFile);
        try {
            BufferedOutputStream bos = new BufferedOutputStream(os);
            Writer w = new OutputStreamWriter(new FileOutputStream(changelogFile), "UTF-8");
            PrintWriter writer = new PrintWriter(w);
            try {


                int cmdResult = launcher.launch().cmds(cmd).envs(env).stdout(bos).join();
                if (cmdResult != 0) {
                    listener.fatalError("Changelog failed with exit code " + cmdResult);
                    result = false;
                }


            } finally {
                writer.close();
                bos.close();
            }
        } finally {
            os.close();
        }

        listener.getLogger().println("Changelog calculated successfully.");
        listener.getLogger().println("Change log file: " + changelogFile.getAbsolutePath());

        return result;
    }

    /**
     * Runs the Surround SCM 'CruiseControl' command to find how many files have changed since the last build.
     *
     * @param project       Project we are using as part of this action
     * @param launcher      Launcher to run the process
     * @param listener      Listener to log the information
     * @param lastBuildDate Previous build date to use as the 'start' for the cruisecontrol command
     * @param currentDate   Current date to use as the 'end' for the cruisecontrol command
     * @param changelogFile File to log the changes
     * @param workspace     Workspace to use to find 'Node' information (path to sscm)
     * @return Returns the # of files that have changed since the lastBuildDate
     * @throws IOException          Throws this if it fails to access the changelogFile
     * @throws InterruptedException Throws this if the launcher fails to run successfully.
     */
    private double determineChangeCount(Job<?, ?> project, Launcher launcher, TaskListener listener, Date lastBuildDate,
                                        Date currentDate, File changelogFile, FilePath workspace) throws IOException, InterruptedException {
        SimpleDateFormat scm_datetime_formatter = new SimpleDateFormat(SURROUND_DATETIME_FORMAT_STR);

        double changesCount = 0;
        if (server != null)
            listener.getLogger().println("in determine Change Count server: " + server);

        String dateRange = scm_datetime_formatter.format(lastBuildDate);
        dateRange = dateRange.concat(":");
        dateRange = dateRange.concat(scm_datetime_formatter.format(currentDate));

        EnvVars env = project.getEnvironment(SSCMUtils.workspaceToNode(workspace), listener);

        ArgumentListBuilder cmd = new ArgumentListBuilder();
        cmd.add(getSscmExe(workspace, listener, null));
        cmd.add("cc");
        cmd.add("/");
        cmd.add("-d".concat(dateRange));
        cmd.add("-b".concat(branch));
        cmd.add("-p".concat(repository));
        cmd.add("-r");
        cmd.add(getServerConnectionArgument(project, env, workspace));
        cmd.addMasked(getUserPasswordArgument(project, env));

        listener.getLogger().println("determineChangeCount executing the command: " + cmd.toString() + " with date range: [ " + dateRange + " ]");

        // TODO: This seems like a stupid hack.  Why are we dumping command output to a text file? Can we guarantee
        //       that the 'changelogFile' (a temp file on some machine) is at an accessible path wherever this is run?
        //       why don't we just read the command output straight into memory & immediately process it?
        FileOutputStream os = new FileOutputStream(changelogFile);
        try {
            BufferedOutputStream bos = new BufferedOutputStream(os);

            try {
                int cmdResult = launcher.launch().cmds(cmd).stdout(bos).join();
                if (cmdResult != 0) {
                    listener.fatalError("Determine changes count failed with exit code " + cmdResult);
                }
            } finally {
                bos.close();
            }
        } finally {
            os.close();
        }

        BufferedReader br = null;
        String line;
        InputStreamReader is = new InputStreamReader(new FileInputStream(changelogFile), "UTF-8");
        try {
            br = new BufferedReader(is);
            line = br.readLine();
            if (line != null) {
                listener.getLogger().println(line);
                String num = line.substring(6);
                try {
                    changesCount = Double.valueOf(num.trim());
                } catch (NumberFormatException nfe) {
                    listener.fatalError("NumberFormatException: " + nfe.getMessage());
                }
            }

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } finally {
            if (br != null) {
                br.close();
            }
        }
        listener.getLogger().println("Number of changes determined to be: " + changesCount);
        return changesCount;
    }

    public void addSscmArgServer(ArgumentListBuilder cmd) {
       cmd.add(String.format("-z%s:%s",server,serverPort));
    }
 
    public void addSscmArgUser(ArgumentListBuilder cmd, Job<?, ?> owner) {
       StandardUsernameCredentials credentials = getCredentials(owner);
       if (credentials != null && credentials instanceof UsernamePasswordCredentials) {
          UsernamePasswordCredentials upc = (UsernamePasswordCredentials) credentials;
          String result = String.format("-y%s:%s", upc.getUsername(), upc.getPassword().getPlainText());
          cmd.addMasked(result);
       }
    }

    /**
     * Attempt to find a pre-configured 'SurroundTool' with a saved 'sscm_tool_name'
     * Currently this will always fall back to the 'default' tool for the current node and requires some further
     * testing of edge conditions
     * 
     * @param listener listener
     * @return the tool
     */
    public SurroundTool resolveSscmTool(TaskListener listener) {
        // TODO_PTV: Review this function, should we allow users to override the sscm_tool_name in the project level?
        // TODO_PTV: Review this function, does this work when a node is configured to use a 2nd Surround SCM tool?
        SurroundTool sscm = null;
        if (sscm_tool_name == null || sscm_tool_name.isEmpty()) {
            sscm = SurroundTool.getDefaultInstallation();
        } else {
            Jenkins jenkinsInstance = Jenkins.getInstance();
            if (jenkinsInstance != null) {
                SurroundTool.DescriptorImpl sscmToolDesc = jenkinsInstance.getDescriptorByType(SurroundTool.DescriptorImpl.class);
                if (sscmToolDesc != null)
                    sscm = sscmToolDesc.getInstallation(sscm_tool_name);
            }
            if (sscm == null) {
                listener.getLogger().println(String.format("Selected sscm installation [%s] does not exist. Using Default", sscm_tool_name));
                sscm = SurroundTool.getDefaultInstallation();
            }
        }

        return sscm;
    }

    private String getSscmExe(FilePath workspace, TaskListener listener, EnvVars env) throws IOException, InterruptedException {
        if (workspace != null) {
            workspace.mkdirs(); // ensure it exists.
        }
        return getSscmExe(SSCMUtils.workspaceToNode(workspace), env, listener);
    }

    /**
     * @return Returns the path to he Surround SCM executable to use.
     */
    private String getSscmExe(Node builtOn, EnvVars env, TaskListener listener) {
        SurroundTool tool = resolveSscmTool(listener);
        if (builtOn != null) {
            try {
                tool = tool.forNode(builtOn, listener);
            } catch (IOException e) {
                listener.getLogger().println("Failed to get sscm executable");
            } catch (InterruptedException e) {
                listener.getLogger().println("Failed to get sscm executable");
            }
        }
        if (env != null) {
            tool = tool.forEnvironment(env);
        }

        return tool.getSscmExe();
    }

    /**
     * Creates the Username // Password argument taking into account that this might be an 'upgraded' plugin
     * that has not yet been modified to use hte more  secure UsernamePasswordCredentials.
     * <p>
     * It first checks to see if it can create a username // password from passed in credentials, then checks to see
     * if there is a legacy username // password saved.
     *
     * @param owner Used to lookup user credentials
     * @param env   Used to lookup user credentials
     * @return Returns the fleshed out username // password "-yUsername:Password" parameter.
     */
    @SuppressWarnings("deprecation")
    // This still references the userName and password fields in cases where users have not updated their configurations.
    private String getUserPasswordArgument(Job<?, ?> owner, EnvVars env) throws IOException {
        String result;
        StandardUsernameCredentials credentials = getCredentials(owner, env);
        if (credentials != null && credentials instanceof UsernamePasswordCredentials) {
            UsernamePasswordCredentials upc = (UsernamePasswordCredentials) credentials;
            result = getUserPasswordArgument(upc);
        } else if (userName != null && !userName.isEmpty()) {
            if (password != null) {
                result = String.format("-y%s:%s", userName, password);
            } else {
                result = String.format("-y%s", userName);
            }
        } else {
            throw new IOException(String.format("Failed to find currently defined username//password credential. [%s] %s",
                    getCredentialsId(), credentials != null ? CredentialsNameProvider.name(credentials) : "Failed to find credential ID"));
        }
        return result;
    }

    private String getUserPasswordArgument(UsernamePasswordCredentials credentials) {
        String result = String.format("-y%s:%s", credentials.getUsername(), credentials.getPassword().getPlainText());
        return result;
    }

    /**
     * Creates the "Server Connection Information" argument used for Surround SCM CLI commands.  Automatically
     * determines if it should pass in a path to an RSA Key File or use the Server // Port setting.
     *
     * @param owner     Used to lookup fileCredentials
     * @param env       Used to lookup fileCredentials
     * @param workspace Used to store any potential key retrieved from fileCredentials
     * @return Fleshed out "-z..." parameter with either server:port or rsaKeyPath.
     */
    private String getServerConnectionArgument(Job<?, ?> owner, EnvVars env, FilePath workspace) {
        String result;
        String rsaKeyPath = getRemotePathForRSAKeyFile(owner, env, workspace);
        if (rsaKeyPath != null && !rsaKeyPath.isEmpty()) {
            result = String.format("-z%s", rsaKeyPath);
        } else {
            result = String.format("-z%s:%s", getServer(), getServerPort());
        }

        return result;
    }

    @CheckForNull
    private StandardUsernameCredentials getCredentials(Job<?, ?> owner) {
        return SSCMUtils.getCredentials(owner, server, serverPort, credentialsId);
    }

    @CheckForNull
    private StandardUsernameCredentials getCredentials(Job<?, ?> owner, EnvVars env) {
        return SSCMUtils.getCredentials(owner, env, server, serverPort, credentialsId);
    }

    @CheckForNull
    private FileCredentials getFileCredentials(Job<?, ?> owner, EnvVars env) {
        return SSCMUtils.getFileCredentials(owner, env, server, serverPort, rsaKey);
    }

    /**
     * Checks to see if there is an existing stored 'fileCredential' for the rsaKeyFileId. If there is, it will write out
     * that file to the remote computer's workspace and return a path to it on the remote computer.
     *
     * @param owner     Used to lookup the fileCredential
     * @param env       Used to expand the possible sscm:// url with build variables.
     * @param workspace Used as the destination for the temp file to be created to be used for the build.
     * @return If there an RSAKeyFile was retrieved from the fileCredentials, this returns the path to the file. Otherwise
     * it returns null.
     */
    private String populateRSAKeyFile(Job<?, ?> owner, EnvVars env, @Nullable FilePath workspace) {
        String result = null;
        FileCredentials fc = getFileCredentials(owner, env);
        if (fc != null && workspace != null) {
            try {
                FilePath rsaFilePath = workspace.createTempFile("RSAKeyFile", ".xml");
                rsaFilePath.copyFrom(fc.getContent());
                result = rsaFilePath.getRemote();
            } catch (IOException e) {
                Logger.getLogger(SurroundSCM.class.toString()).log(Level.SEVERE,
                        String.format("Found RSA Key File by ID [%s], however failed to retrieve file to destination machine.%n" +
                                "Error Message: %s", rsaKey != null ? rsaKey.getRsaKeyValue() : "rsaKey object was null?", e.toString()));
            } catch (InterruptedException e) {
                Logger.getLogger(SurroundSCM.class.toString()).log(Level.SEVERE,
                        String.format("Exception while attempting to retrieve RSA Key File to destination machine. Error message: %s", e.toString()));
            }
        }

        return result;
    }

    /**
     * This first checks the rsaKeyFileId and then the rsaKeyPath.  If it can find a path to an RSA Key file from either
     * of these items it will return the path to the RSA Key file on the remote machine (or as defined via rsaKeyPath)
     * If no RSA Key file is found, it returns null
     *
     * @param owner     Used to lookup possible fileCredentials
     * @param env       Used as part of the lookup for fileCredentials
     * @param workspace Used as a destination for any RSA Key File retrieved from fileCredentials
     * @return Returns either the path to an RSA Key File, or null indicating no RSA Key File.
     */
    private String getRemotePathForRSAKeyFile(Job<?, ?> owner, EnvVars env, FilePath workspace) {
        String result = null;
        if (rsaKey != null) {
            switch (rsaKey.getRsaKeyType()) {
                case ID:
                    result = populateRSAKeyFile(owner, env, workspace);
                    break;
                case Path:
                    result = rsaKey.getRsaKeyValue();
                    break;
                case NoKey:
                default:
                    result = null;
            }
        } else if (rsaKeyPath != null && !rsaKeyPath.isEmpty()) {
            result = rsaKeyPath;
        }
        return result;
    }

    @SuppressWarnings("WeakerAccess")
    @Extension
    public static class SurroundSCMDescriptor extends SCMDescriptor<SurroundSCM> {

        /**
         * Constructs a new SurroundSCMDescriptor.
         */
        public SurroundSCMDescriptor() {
            super(SurroundSCM.class, null);
            load();
        }

        @Override
        public boolean isApplicable(Job project) {
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getDisplayName() {
            return "Surround SCM";
        }

        @Override
        public SCM newInstance(StaplerRequest req, JSONObject formData)
                throws FormException {
            return req.bindJSON(SurroundSCM.class, formData);
        }

//        /**
//         * This populates the Username//Password credential dropdown on the config page.
//         *
//         * @return Returns a list of credentials to populate the combobox with.
//         */
//        @SuppressWarnings("unused") // This is called via Stapler
//        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Job<?, ?> owner, @QueryParameter String source) {
//            return SSCMUtils.doFillCredentialsIdItems(owner, source);
//        }

        @SuppressWarnings("unused")
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item context, @QueryParameter String remote) {
            return SSCMUtils.doFillCredentialsIdItems(context, remote);
        }



        /**
         * This populates the rsaKeyFileId dropdown with a list of 'FileCredentials' that could be used.
         *
         * @param context context
         * @param remote remote
         * @return Returns a list of FileCredential objects that have been configured.
         */
        @SuppressWarnings("unused") // This is called via Stapler
        public ListBoxModel doFillRsaKeyFileIdItems(@AncestorInPath Item context, @QueryParameter String remote) {
             return SSCMUtils.doFillRsaKeyFileIdItems(context, remote);
        }

        /**
         * I am honestly not sure if this is required... I kinda think this can get removed w/o breaking anything.
         */
        @SuppressWarnings("unused")
        // TODO: See if I can delete getRSAKeyFromRequest safely. Possibly called from stapler?
        private RSAKey getRSAKeyFromRequest(final StaplerRequest req, final JSONObject scmData) {
            if (scmData.containsKey("RSAKey")) {
                return req.bindJSON(RSAKey.class, scmData.getJSONObject("RSAKey"));
            } else {
                return null;
            }
        }
    }
}
