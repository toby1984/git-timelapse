/**
 * Copyright 2014 Tobias Gierke <tobias.gierke@code-sourcery.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.codesourcery.gittimelapse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.errors.StopWalkException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import de.codesourcery.gittimelapse.PathModel.PathChangeModel;

/**
 * Various helper methods to make dealing with GIT plumbing easier.
 * 
 * @author tobias.gierke@code-sourcery.de
 */
public class GitHelper {

	private final Repository repository;
	private final File gitDir;
	private final File repoBaseDir;
	private final File currentWorkingDir;

	public interface ICommitVisitor 
	{
		public boolean visit(RevCommit commit) throws IOException;
	}

	public interface ITreeVisitor {
		public boolean visit(TreeWalk walker) throws IOException;
	}

	public final class CommitList implements Iterable<ObjectId>
	{
		private final List<ObjectId> commits = new ArrayList<>();

		private final File file;

		public CommitList(File file) {
			if (file == null) {
				throw new IllegalArgumentException("file must not be NULL");
			}
			this.file=file;
		}

		public int indexOf(ObjectId current) 
		{
			if (current == null) {
				throw new IllegalArgumentException("commit must not be NULL");
			}
			final int size = commits.size();
			for ( int i = 0 ; i < size ; i++ ) {
				if ( current.equals( commits.get(i) ) ) {
					return i;
				}
			}
			return -1;
		}

		protected void add(ObjectId id) {
			if (id == null) {
				throw new IllegalArgumentException("id must not be NULL");
			}
			this.commits.add(id);
		}
		
		public void reverse() {
			Collections.reverse( commits );
		}

		public ObjectId getPredecessor(ObjectId current) 
		{
			final int idx = indexOf(current);
			return idx > 0 ? commits.get(idx-1) : null;
		}		

		public void visitCommit(ObjectId commit, ICommitVisitor visitor) throws IOException {
			GitHelper.this.visitSingleCommit( commit , visitor );
		}

		public Iterator<ObjectId> iterator() {
			return commits.iterator();
		}

		public byte[] readFile(ObjectId commit) throws IOException 
		{
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			final String path = stripRepoBaseDir( this.file );
			final PathFilter filter = createPathFilter( this.file );
			final ICommitVisitor func = new ICommitVisitor() {

				@Override
				public boolean visit(RevCommit commit) throws IOException 
				{
					GitHelper.this.readFile(path, filter, commit, out);
					return false;
				}
			};
			visitCommits( this.file , false , commit , func );
			return out.toByteArray();
		}

		public boolean isEmpty() {
			return commits.isEmpty();
		}

		public int size() {
			return commits.size();
		}

		public ObjectId getLatestCommit() {
			return isEmpty() ? null : commits.get( commits.size()-1 );
		}

		public ObjectId getCommit(int i) 
		{
			if ( i < 0 || i >= commits.size() ) {
				throw new IndexOutOfBoundsException("No commit no. "+i);
			}
			return commits.get(i);
		}
	}

	public GitHelper(File currentWorkingDir) throws IOException 
	{
		if (currentWorkingDir == null) {
			throw new IllegalArgumentException("currentWorkingDir must not be NULL");
		}

		this.currentWorkingDir = currentWorkingDir;
		this.gitDir = findGitDir( this.currentWorkingDir ) ;
		this.repoBaseDir = this.gitDir.getParentFile();

		final FileRepositoryBuilder builder = new FileRepositoryBuilder();
		builder.setGitDir( gitDir );
		builder.readEnvironment(); // scan environment GIT_* variables
		repository = builder.build();		
	}

	private File findGitDir(File directory) {

		if ( ! directory.exists() ) {
			throw new IllegalArgumentException("CWD "+directory+" does not exist?");
		}
		if ( ! directory.isDirectory() ) {
			throw new IllegalArgumentException("CWD "+directory+" is not a directory?");
		}		
		File tmp = new File(directory,".git");
		do {
			if ( tmp.exists() ) {
				return tmp;
			}
			tmp = tmp.getParentFile();
		} while( tmp != null );
		throw new RuntimeException("Failed to find .git directory in subtree ending at "+directory.getAbsolutePath());
	}

	public void traverse(File localPath) throws RevisionSyntaxException, MissingObjectException, IncorrectObjectTypeException, AmbiguousObjectException, IOException, GitAPIException 
	{
		final RevWalk walk = new RevWalk( repository );
		try {
			final ObjectId head = repository.resolve("HEAD");
			if ( head == null ) {
				throw new RuntimeException("Failed to resolve HEAD ?");
			}

			final RevCommit startCommit = walk.parseCommit(head);
			walk.markStart(startCommit);

			// setup path filter
			final String path = stripRepoBaseDir( localPath );
			final PathFilter filter = createPathFilter(path);

			for ( RevCommit currentCommit : walk ) 
			{
				readFile(path, filter, currentCommit);
			}
		} finally {
			walk.dispose();
		}
	}

	public CommitList findCommits(final File localPath) throws RevisionSyntaxException, MissingObjectException, IncorrectObjectTypeException, AmbiguousObjectException, IOException, GitAPIException 
	{
		final CommitList result = new CommitList(localPath);

		final String strippedPath = stripRepoBaseDir( localPath );
		final PathFilter filter = createPathFilter( localPath );

		final ICommitVisitor func = new ICommitVisitor() {

			@Override
			public boolean visit(RevCommit commit) throws IOException 
			{
				if ( commitChangesFile( strippedPath , filter , commit ) ) {
					result.add( commit.getId() );
				}
				return true;
			}
		};
		visitCommits( localPath , false , func );
		
		// reverse commits so they are in chronological order
		result.reverse();
		return result;
	}		

	protected void visitCommits(File localPath,boolean retainCommitBody,ObjectId startCommit, ICommitVisitor func) throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException 
	{
		if ( startCommit == null ) {
			throw new RuntimeException("startCommit must not be NULL");
		}		

		final RevWalk walk = new RevWalk( repository );
		try 
		{
			final String path = stripRepoBaseDir( localPath );
			final PathFilter filter = createPathFilter(path);
			TreeFilter group = PathFilterGroup.create( Collections.singleton( filter ) );

			walk.setTreeFilter( group );
			walk.setRevFilter( new RevFilter() {

				@Override
				public boolean include(RevWalk walker, RevCommit cmit) throws StopWalkException, MissingObjectException, IncorrectObjectTypeException, IOException 
				{
					return commitChangesFile( path , filter , cmit );
				}

				@Override
				public RevFilter clone() {
					return this;
				}
			});
			walk.setRetainBody(retainCommitBody);

			final RevCommit startingCommit = walk.parseCommit(startCommit);
			walk.markStart(startingCommit);
			visitCommits( walk , func );
		} finally {
			walk.dispose();
		}
	}

	protected void visitCommits(File localPath,boolean retainCommitBody,ICommitVisitor func) throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException 
	{
		final ObjectId head = repository.resolve("HEAD");
		visitCommits(localPath , retainCommitBody , head , func );
	}

	protected void visitCommits(RevWalk walk, ICommitVisitor func) throws RevisionSyntaxException, AmbiguousObjectException, IncorrectObjectTypeException, IOException 
	{
		for ( RevCommit currentCommit : walk ) 
		{
			if ( ! func.visit( currentCommit ) ) {
				return;
			}
		}
	}

	protected PathFilter createPathFilter(File file) 
	{
		return PathFilter.create( stripRepoBaseDir(file) );
	}

	protected PathFilter createPathFilter(String path) 
	{
		return PathFilter.create( stripRepoBaseDir(path) );
	}

	protected String stripRepoBaseDir(File path) 
	{
		return stripRepoBaseDir(path.getPath() );
	}

	protected String stripRepoBaseDir(String path) 
	{
		final String baseDir = repoBaseDir.getAbsolutePath();
		if ( path.startsWith( baseDir ) ) {
			path = path.substring( repoBaseDir.getAbsolutePath().length() );
			if ( path.startsWith( File.separator ) ) { // strip leading '/'
				path = path.substring(1);
			}
		}
		return path;
	}

	protected boolean commitChangesFile(String path, PathFilter filter,RevCommit currentCommit) throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException, IOException 
	{
		Set<String> filesInCommit = getFilesInCommit( currentCommit );
		return filesInCommit.contains( stripRepoBaseDir( path ) );
	}	

	protected byte[] readFile(File file, ObjectId commit) throws MissingObjectException, IncorrectObjectTypeException,CorruptObjectException, IOException 
	{
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final ICommitVisitor func = new ICommitVisitor() {

			@Override
			public boolean visit(RevCommit commit) {
				return false;
			}
		};
		visitCommits(file,false,commit , func );
		return out.toByteArray();
	}

	protected byte[] readFile(String path, PathFilter filter, RevCommit current) throws MissingObjectException, IncorrectObjectTypeException,CorruptObjectException, IOException 
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		readFile(path,filter,current,out);
		return out.toByteArray();
	}

	protected void readFile(String path, PathFilter filter, RevCommit current,final ByteArrayOutputStream buffer) throws MissingObjectException, IncorrectObjectTypeException,CorruptObjectException, IOException 
	{
		final String strippedPath = stripRepoBaseDir( path );
		final ITreeVisitor visitor = new ITreeVisitor() {

			@Override
			public boolean visit(TreeWalk treeWalk) throws MissingObjectException, IOException 
			{
				final CanonicalTreeParser parser = treeWalk.getTree( 0 , CanonicalTreeParser.class );
				while ( ! parser.eof() ) 
				{
					if ( parser.getEntryPathString().equals( strippedPath ) ) {
						ObjectLoader loader = repository.open(parser.getEntryObjectId() );
						buffer.write( loader.getBytes() );						
					}
					parser.next(1);
				}			
				return true;
			}
		};
		visitCommitTree( path , filter , current , visitor );
	}

	protected void visitCommitTree(String path, PathFilter filter, RevCommit current,ITreeVisitor treeVisitor) throws MissingObjectException, IncorrectObjectTypeException,CorruptObjectException, IOException 
	{
		final String cleanPath = stripRepoBaseDir( path );
		int cleanPathSize = 0;
		for ( int i = 0 ; i < cleanPath.length() ; i++ ) {
			if ( cleanPath.charAt(i) == '/' ) {
				cleanPathSize++;
			}
		}

		final TreeWalk tree = new TreeWalk(repository);
		try {
			tree.setFilter( filter );
			tree.setRecursive(true);
			tree.addTree( current.getTree() );

			/*
while (tree.next)
    if (tree.getDepth == cleanPath.size) {
        // we are at the right level, do what you want
    } else {
        if (tree.isSubtree &&
            name == cleanPath(tree.getDepth)) {
            tree.enterSubtree
        }
    }
}				 
			 */		
			while( tree.next() ) 
			{
				if ( tree.getDepth() == cleanPathSize ) 
				{
					if ( ! treeVisitor.visit( tree ) ) 
					{
						return;
					}
				}
				if ( tree.isSubtree() ) {
					tree.enterSubtree();
				}
			}
		} finally {
			tree.release();
		}
	}

	public void visitSingleCommit(ObjectId id, ICommitVisitor visitor) throws IOException {

		if ( id == null ) {
			throw new RuntimeException("commit ID must not be NULL");
		}		

		final RevWalk walk = new RevWalk( repository );
		try {
			walk.setRetainBody(true);

			final RevCommit startingCommit = walk.parseCommit(id);
			walk.markStart(startingCommit);

			RevCommit current=walk.next();
			visitor.visit( current );
		} finally {
			walk.dispose();
		}
	}

	public Repository getRepository() {
		return repository;
	}	

	private Set<String> getFilesInCommit(RevCommit commit) throws IOException 
	{
		if ( commit == null ) {
			throw new IllegalArgumentException("commit must not be NULL");
		}
		
		List<PathChangeModel> list = new ArrayList<>();
		
		final RevWalk rw = new RevWalk(repository);
		try {
			if (commit.getParentCount() == 0) {
				TreeWalk tw = new TreeWalk(repository);
				tw.reset();
				tw.setRecursive(true);
				tw.addTree(commit.getTree());
				while (tw.next()) {
					list.add(new PathChangeModel(tw.getPathString(), tw.getPathString(), 0, tw
							.getRawMode(0), tw.getObjectId(0).getName(), commit.getId().getName(),
							ChangeType.ADD));
				}
				tw.release();
			} 
			else 
			{
				RevCommit parent = rw.parseCommit(commit.getParent(0).getId());
				
				final ByteArrayOutputStream out = new ByteArrayOutputStream();
				final DiffFormatter df = new DiffFormatter(out);
				df.setRepository(repository);
				df.setDiffComparator(RawTextComparator.DEFAULT);
				df.setDetectRenames(true);
				
				final List<DiffEntry> diffs = df.scan(parent.getTree(), commit.getTree());
				for (DiffEntry diff : diffs) {
					// create the path change model
					PathChangeModel pcm = PathChangeModel.from(diff, commit.getName());
					list.add(pcm);
				}
			}
		} finally {
			rw.dispose();
		}
		Set<String> result = new HashSet<>();
		for ( PathChangeModel model : list ) 
		{
			if ( model.isFile() ) {
				result.add( model.path );
			}
		}
		return result;
	}	
}