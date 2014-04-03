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

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.ApplyCommand;
import org.eclipse.jgit.api.errors.PatchApplyException;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.HunkHeader;
import org.eclipse.jgit.patch.Patch;

/**
 * Abstraction for a text file along with line start offsets and some
 * utility methods for applying unified diffs.
 * 
 * <p>Code to apply diffs was adopted from the JGit {@link ApplyCommand}.</p>
 * 
 * @author tobias.gierke@voipfuture.com
 */
public class TextFile {

	private RawText rawText;
	private final Map<Integer,Integer> lineStartOffsets = new HashMap<>();

	public static enum ChangeType {
		NO_CHANGE,
		ADDED,
		DELETED;
	}

	private final Map<Integer,ChangeType> changesByLine = new HashMap<>();

	public TextFile(RawText text)
	{
		this.rawText = text;
		updateLineStartOffsets();
	}

	@Override
	public String toString()
	{
		if ( rawText.size() == 0 ) {
			return "";
		}
		return rawText.getString(0,rawText.size(),false);
	}

	public void setText(String text)
	{
		this.rawText = new RawText( text.getBytes() );
		updateLineStartOffsets();
	}

	private void updateLineStartOffsets()
	{
		this.lineStartOffsets.clear();
		final int lineCount = rawText.size();

		int offset = 0;
		for ( int i = 0 ; i < lineCount ; i++ ) {
			lineStartOffsets.put( i , offset );
			offset += rawText.getString( i ).length()+1; // +1 because of trailing LF
		}
	}

	public int getLineCount() {
		return rawText.size();
	}

	public Map<Integer,ChangeType> getChangedLines() {
		return Collections.unmodifiableMap( changesByLine );
	}

	public ChangeType getChangeType( int line) {
		final ChangeType result = changesByLine.get(line);
		if ( line < 0 || line >= rawText.size() ) {
			throw new IndexOutOfBoundsException("Invalid line "+line+" , max. = "+(rawText.size()-1));
		}
		return result != null ? result : ChangeType.NO_CHANGE;
	}

	public int getLineStartOffset(int line) {
		final Integer result = lineStartOffsets.get( line );
		if ( line < 0 || line >= rawText.size() ) {
			throw new IndexOutOfBoundsException("Invalid line "+line+" , max. = "+(rawText.size()-1));
		}
		return result.intValue();
	}

	public int getLineEndOffset(int line) {
		return getLineStartOffset( line ) + rawText.getString( line ).length()+1;
	}

	// method taken from JGit ApplyCommand and modified
	public void forwardsPatchAndAlign(Patch patch) throws IOException, PatchApplyException
	{
		if ( patch.getFiles().size() != 1 ) {
			throw new IllegalArgumentException("Patch needs to contain exactly 1 FileHeader");
		}
		final FileHeader fh = patch.getFiles().get(0);

		final List<String> oldLines = new ArrayList<String>(rawText.size());
		for (int i = 0; i < rawText.size(); i++) {
			oldLines.add(rawText.getString(i));
		}

		final List<String> newLines = new ArrayList<String>(oldLines);
		for (final HunkHeader hunkHeader : fh.getHunks())
		{
			final StringBuilder hunk = new StringBuilder();
			for (int j = hunkHeader.getStartOffset(); j < hunkHeader.getEndOffset(); j++)
			{
				hunk.append((char) hunkHeader.getBuffer()[j]);
			}

			final RawText hunkRawText = new RawText(hunk.toString().getBytes());

			final List<String> hunkLines = new ArrayList<String>(hunkRawText.size());
			for (int i = 0; i < hunkRawText.size(); i++)
			{
				hunkLines.add(hunkRawText.getString(i));
			}

			int pos = 0;
			for (int j = 1; j < hunkLines.size(); j++)
			{
				final String hunkLine = hunkLines.get(j);
				switch ( hunkLine.charAt(0) )
				{
				case ' ': // context line
					pos++;
					break;
				case '-': // line removed
					changesByLine.put( hunkHeader.getNewStartLine() - 1 + pos , ChangeType.DELETED );
					pos++;
					break;
				case '+': // line added
					changesByLine.put( hunkHeader.getNewStartLine() - 1 + pos , ChangeType.ADDED );
					newLines.add(hunkHeader.getNewStartLine() - 1 + pos, "               " ); // JTextPane uses a colored background so we need to have SOMETHING on this line
					pos++;
					break;
				}
			}
		}
		if (!isNoNewlineAtEndOfFile(fh))
		{
			newLines.add(""); //$NON-NLS-1$
		}
		if (!rawText.isMissingNewlineAtEnd())
		{
			oldLines.add(""); //$NON-NLS-1$
		}
		final StringBuilder sb = new StringBuilder();
		for (final String l : newLines) {
			// don't bother handling line endings - if it was windows, the \r is
			// still there!
			sb.append(l).append('\n');
		}
		sb.deleteCharAt(sb.length() - 1);
		setText( sb.toString() );
	}

	// method copied from JGit ApplyCommand
	private static boolean isNoNewlineAtEndOfFile(FileHeader fh) {
		final HunkHeader lastHunk = fh.getHunks().get(fh.getHunks().size() - 1);
		final RawText lhrt = new RawText(lastHunk.getBuffer());
		return lhrt.getString(lhrt.size() - 1).equals(
				"\\ No newline at end of file"); //$NON-NLS-1$
	}

	public static String toString(RawText rt)
	{
		return rt.getString( 0 , rt.size() , false );
	}

	// method copied from JGit ApplyCommand
	public void backwardsPatchAndAlign(Patch patch) throws IOException, PatchApplyException
	{
		if ( patch.getFiles().size() != 1 ) {
			throw new IllegalArgumentException("Patch needs to contain exactly one FileHeader");
		}

		final FileHeader fh = patch.getFiles().get(0);
		final List<String> oldLines = new ArrayList<String>(rawText.size());
		for (int i = 0; i < rawText.size(); i++) {
			oldLines.add(rawText.getString(i));
		}

		final List<String> newLines = new ArrayList<String>(oldLines);
		for (final HunkHeader hunkHeader : fh.getHunks())
		{
			final StringBuilder hunk = new StringBuilder();
			for (int j = hunkHeader.getStartOffset(); j < hunkHeader.getEndOffset(); j++)
			{
				hunk.append((char) hunkHeader.getBuffer()[j]);
			}

			final RawText hunkRawText = new RawText(hunk.toString().getBytes());

			final List<String> hunkLines = new ArrayList<String>(hunkRawText.size());
			for (int i = 0; i < hunkRawText.size(); i++)
			{
				hunkLines.add(hunkRawText.getString(i));
			}

			int pos = 0;
			for (int j = 1; j < hunkLines.size(); j++)
			{
				final String hunkLine = hunkLines.get(j);
				switch ( hunkLine.charAt(0) )
				{
				case ' ':
					pos++;
					break;
				case '+':
					changesByLine.put( hunkHeader.getNewStartLine() - 1 + pos , ChangeType.DELETED );
					newLines.add( hunkHeader.getNewStartLine() - 1 + pos , "               ");
					pos++;
					break;
				case '-':
					changesByLine.put ( hunkHeader.getNewStartLine() - 1 + pos , ChangeType.ADDED );
					pos++;
					break;
				}
			}
		}
		if (!isNoNewlineAtEndOfFile(fh))
		{
			newLines.add(""); //$NON-NLS-1$
		}
		if (!rawText.isMissingNewlineAtEnd())
		{
			oldLines.add(""); //$NON-NLS-1$
		}

		final StringBuilder sb = new StringBuilder();
		for (final String l : newLines) {
			// don't bother handling line endings - if it was windows, the \r is
			// still there!
			sb.append(l).append('\n');
		}
		sb.deleteCharAt(sb.length() - 1);
		setText( sb.toString() );
	}

	// method copied from JGit ApplyCommand
	public void backwardsPatch(Patch patch) throws IOException, PatchApplyException
	{
		if ( patch.getFiles().size() != 1 ) {
			throw new IllegalArgumentException("Patch needs to contain exactly one FileHeader");
		}

		final FileHeader fh = patch.getFiles().get(0);
		
		final List<String> oldLines = new ArrayList<String>(rawText.size());
		for (int i = 0; i < rawText.size(); i++) {
			oldLines.add(rawText.getString(i));
		}
		final List<String> newLines = new ArrayList<String>(oldLines);
		for (final HunkHeader hh : fh.getHunks()) 
		{
			final StringBuilder hunk = new StringBuilder();
			for (int j = hh.getStartOffset(); j < hh.getEndOffset(); j++) 
			{
				hunk.append((char) hh.getBuffer()[j]);
			}
			final RawText hrt = new RawText(hunk.toString().getBytes());
			final List<String> hunkLines = new ArrayList<String>(hrt.size());
			for (int i = 0; i < hrt.size(); i++) {
				hunkLines.add(hrt.getString(i));
			}
			int pos = 0;
			for (int j = 1; j < hunkLines.size(); j++) {
				final String hunkLine = hunkLines.get(j);
				switch (hunkLine.charAt(0)) {
				case ' ':
					if (!newLines.get(hh.getNewStartLine() - 1 + pos).equals(
							hunkLine.substring(1))) {
						throw new PatchApplyException(MessageFormat.format(
								JGitText.get().patchApplyException, hh));
					}
					pos++;
					break;
				case '-':
					if (!newLines.get(hh.getNewStartLine() - 1 + pos).equals(
							hunkLine.substring(1))) {
						throw new PatchApplyException(MessageFormat.format(
								JGitText.get().patchApplyException, hh));
					}
					newLines.remove(hh.getNewStartLine() - 1 + pos);
					break;
				case '+':
					newLines.add(hh.getNewStartLine() - 1 + pos,hunkLine.substring(1));
					changesByLine.put(hh.getNewStartLine() - 1 + pos, ChangeType.ADDED);
					pos++;
					break;
				}
			}
		}
		if (!isNoNewlineAtEndOfFile(fh))
		{
			newLines.add(""); //$NON-NLS-1$
		}

		if (!rawText.isMissingNewlineAtEnd())
		{
			oldLines.add(""); //$NON-NLS-1$
		}

		final StringBuilder sb = new StringBuilder();
		for (final String l : newLines) {
			// don't bother handling line endings - if it was windows, the \r is
			// still there!
			sb.append(l).append('\n');
		}
		sb.deleteCharAt(sb.length() - 1);
		setText( sb.toString() );
	}
	
	// method copied from JGit ApplyCommand
	public void forwardsPatch(Patch patch) throws IOException, PatchApplyException
	{
		if ( patch.getFiles().size() != 1 ) {
			throw new IllegalArgumentException("Patch needs to contain exactly one FileHeader");
		}

		final FileHeader fh = patch.getFiles().get(0);
		
		final List<String> oldLines = new ArrayList<String>(rawText.size());
		for (int i = 0; i < rawText.size(); i++) {
			oldLines.add(rawText.getString(i));
		}
		final List<String> newLines = new ArrayList<String>(oldLines);
		for (final HunkHeader hh : fh.getHunks()) 
		{
			final StringBuilder hunk = new StringBuilder();
			for (int j = hh.getStartOffset(); j < hh.getEndOffset(); j++) 
			{
				hunk.append((char) hh.getBuffer()[j]);
			}
			final RawText hrt = new RawText(hunk.toString().getBytes());
			final List<String> hunkLines = new ArrayList<String>(hrt.size());
			for (int i = 0; i < hrt.size(); i++) {
				hunkLines.add(hrt.getString(i));
			}
			int pos = 0;
			for (int j = 1; j < hunkLines.size(); j++) {
				final String hunkLine = hunkLines.get(j);
				switch (hunkLine.charAt(0)) {
				case ' ':
					pos++;
					break;
				case '-':
					changesByLine.put(hh.getNewStartLine() - 1 + pos, ChangeType.DELETED );					
					pos++;
					break;
				case '+':
					break;
				}
			}
		}
		if (!isNoNewlineAtEndOfFile(fh))
		{
			newLines.add(""); //$NON-NLS-1$
		}

		if (!rawText.isMissingNewlineAtEnd())
		{
			oldLines.add(""); //$NON-NLS-1$
		}

		final StringBuilder sb = new StringBuilder();
		for (final String l : newLines) {
			// don't bother handling line endings - if it was windows, the \r is
			// still there!
			sb.append(l).append('\n');
		}
		sb.deleteCharAt(sb.length() - 1);
		setText( sb.toString() );
	}	
}