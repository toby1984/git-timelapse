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
import java.util.Stack;

import org.apache.commons.lang.ArrayUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RevisionSyntaxException;

public class Main {
	
	public static boolean DEBUG_MODE = false;
	
	public static void main(String[] args) throws IOException, RevisionSyntaxException, GitAPIException 
	{
		final Stack<String> argStack = new Stack<>();
		
		final String testFile = "/home/tgierke/workspace/voipmanager/voipmngr/voipmngr/build.xml";
		if ( ArrayUtils.isEmpty( args ) && new File(testFile).exists() ) 
		{
			argStack.push( testFile );
		}
		
		File file = null;
		while ( ! argStack.isEmpty() ) {
			if ( "-d".equals( argStack.peek() ) ) {
				DEBUG_MODE = true;
				argStack.pop();
			} else {
				file = new File( argStack.pop() );
			}
		}
		
		if ( file == null )
		{
			System.err.println("ERROR: Invalid command line.");
			System.err.println("Usage: [-d] <versioned file>\n");
			return;
		}
		
		final GitHelper helper = new GitHelper(file.getParentFile());
		
		MyFrame frame = new MyFrame(file,helper);
		frame.setPreferredSize(new Dimension(640,480));
		frame.pack();
		frame.setVisible( true );
	}
}