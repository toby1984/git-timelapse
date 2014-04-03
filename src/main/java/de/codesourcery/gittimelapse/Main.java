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

import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.lang.ArrayUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RevisionSyntaxException;

public class Main {
	
	public static void main(String[] args) throws IOException, RevisionSyntaxException, GitAPIException {
		
		if ( ArrayUtils.isEmpty( args ) && new File("/home/tobi/kepler_workspace/gittimelapse/src/main/java/gittimelapse/Main.java").exists() ) 
		{
			args = new String[]{"/home/tobi/kepler_workspace/gittimelapse/src/main/java/gittimelapse/Main.java"};
		}
		
		if ( ArrayUtils.isEmpty( args ) || args.length != 1) {
			System.err.println("ERROR: Invalid command line.");
			System.err.println("Usage: <versioned file>\n");
			return;
		}
		
		final File file = new File( args[0] );
		
		Path cwd = Paths.get("").toAbsolutePath();
		
		final GitHelper helper = new GitHelper(cwd.toFile());
		
		MyFrame frame = new MyFrame(file,helper);
		frame.setPreferredSize(new Dimension(640,480));
		frame.pack();
		frame.setVisible( true );
	}
}