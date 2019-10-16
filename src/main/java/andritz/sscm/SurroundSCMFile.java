package andritz.sscm;

import edu.umd.cs.findbugs.annotations.NonNull;
import jenkins.scm.api.SCMFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class SurroundSCMFile extends SCMFile {

	private final SurroundSCMFileSystem fs;
	private final boolean isDir;
	private final NavigateHelper nav;

	public SurroundSCMFile(SurroundSCMFileSystem fs) {
		this.fs = fs;
		this.isDir = true;
		this.nav = new NavigateHelper(fs);
	}

	public SurroundSCMFile(SurroundSCMFileSystem fs, @NonNull SurroundSCMFile parent, String name, boolean isDir) {
		super(parent, name);
		this.fs = fs;
		this.isDir = isDir;
		this.nav = new NavigateHelper(fs);
	}

	@Override
	protected SCMFile newChild(@NonNull String name, boolean assumeIsDirectory) {
		return new SurroundSCMFile(fs, this, name, assumeIsDirectory);
	}

	/**
	 * If this object represents a directory, lists up all the immediate children.
	 *
	 * @return Always non-null. If this method is not a directory, this method returns
	 * an empty iterable.
	 * @throws IOException          if an error occurs while performing the operation.
	 * @throws InterruptedException if interrupted while performing the operation.
	 */
	@Override
	public Iterable<SCMFile> children() throws IOException, InterruptedException {
		String path = getPath();
		List<SCMFile> list = new ArrayList<>();

      List<NavigateHelper.Node> nodes = nav.getNodes(path);
		for (NavigateHelper.Node node : nodes) {
			list.add(newChild(node.getName(), node.isDir()));
		}

		return list;
	}

	/**
	 * Returns the time that the {@link SCMFile} was last modified.
	 *
	 * @return A <code>long</code> value representing the time the file was last modified, measured in milliseconds
	 * since the epoch (00:00:00 GMT, January 1, 1970) or {@code 0L} if the operation is unsupported.
	 * @throws IOException          if an error occurs while performing the operation.
	 * @throws InterruptedException if interrupted while performing the operation.
	 */
	@Override
	public long lastModified() throws IOException, InterruptedException {

		// ConnectionHelper p4 = fs.getConnection();
		// List<IFileSpec> file = getFileSpec();

		// GetExtendedFilesOptions exOpts = new GetExtendedFilesOptions();
		// try {
		// 	List<IExtendedFileSpec> fstat = p4.getConnection().getExtendedFiles(file, exOpts);
		// 	if(fstat.get(0).getOpStatus().equals(FileSpecOpStatus.VALID)) {
		// 		Date date = fstat.get(0).getHeadModTime();
		// 		return date.getTime();
		// 	}
		// } catch (P4JavaException e) {
		// 	throw new IOException(e);
		// }
		return 0;
	}

	/**
	 * The type of this object.
	 *
	 * @return the {@link Type} of this object, specifically {@link Type#NONEXISTENT} if this {@link SCMFile} instance
	 * does not exist in the remote system (e.g. if you created a nonexistent instance via {@link #child(String)})
	 * @throws IOException          if an error occurs while performing the operation.
	 * @throws InterruptedException if interrupted while performing the operation.
	 * @since 2.0
	 */
	@Override
	protected Type type() throws IOException, InterruptedException {
		if(isDir) {
			return Type.DIRECTORY;
		}
		return Type.REGULAR_FILE;
	}

	/**
	 * Reads the content of this file.
	 *
	 * @return an open stream to read the file content. The caller must close the stream.
	 * @throws IOException          if this object represents a directory or if an error occurs while performing the
	 *                              operation.
	 * @throws InterruptedException if interrupted while performing the operation.
	 */
	@Override
	public InputStream content() throws IOException, InterruptedException {
		return nav.getFileContent(getPath());
	}
}
