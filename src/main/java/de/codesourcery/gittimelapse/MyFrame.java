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

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.Map.Entry;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.PatchApplyException;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.errors.*;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.patch.Patch;
import org.eclipse.jgit.revwalk.RevCommit;

import de.codesourcery.gittimelapse.GitHelper.ICommitVisitor;
import de.codesourcery.gittimelapse.GitHelper.IProgressCallback;
import de.codesourcery.gittimelapse.TextFile.ChangeType;

public class MyFrame extends JFrame {

	private final File file;
	private final DiffPanel diffPanel;
	private final JSlider revisionSlider;
	private final GitHelper gitHelper;
	private final GitHelper.CommitList commitList;
	private final JComboBox<DiffDisplayMode> diffModeChooser = new JComboBox<>();

	private boolean adjustmentListenerActive = true;

	protected static final class LineOffsets 
	{
		public final int lineNumber;
		public final int start;
		public final int end;

		public LineOffsets(int lineNumber,int start,int end) {
			if ( lineNumber < 0 ) {
				throw new IllegalArgumentException("Invalid line number");
			}
			if ( start < 0 || end < 0 ) {
				throw new IllegalArgumentException("Invalid line start/end offset");
			}
			if ( end < start ) {
				throw new IllegalArgumentException("Invalid line start/end offset");
			}
			this.lineNumber = lineNumber;
			this.start=start;
			this.end=end;
		}

		public boolean containsOffset(int offset) {
			return start <= offset && offset < end;
		}
	}	

	protected static enum DiffDisplayMode {
		ALIGN_CHANGES,
		REGULAR;
	}

	protected final KeyAdapter keyListener = new KeyAdapter() {

		public void keyReleased(java.awt.event.KeyEvent e) 
		{
			int currentRevisionIndex = revisionSlider.getValue();
			if ( e.getKeyCode() == KeyEvent.VK_LEFT && currentRevisionIndex > 1 ) {
				revisionSlider.setValue( currentRevisionIndex - 1 );
			} else if ( e.getKeyCode() == KeyEvent.VK_RIGHT && currentRevisionIndex < commitList.size() ) {
				revisionSlider.setValue( currentRevisionIndex + 1 );
			}
		}
	};


	public MyFrame(File file,GitHelper gitHelper) throws RevisionSyntaxException, MissingObjectException, IncorrectObjectTypeException, AmbiguousObjectException, IOException, GitAPIException 
	{
		super("GIT timelapse: "+file.getAbsolutePath());
		if ( gitHelper == null ) {
			throw new IllegalArgumentException("gitHelper must not be NULL");
		}

		this.gitHelper = gitHelper;
		this.file=file;
		this.diffPanel = new DiffPanel();

		final JDialog dialog = new JDialog((Frame) null,"Please wait...",false);
		dialog.getContentPane().setLayout( new BorderLayout() );
		dialog.getContentPane().add( new JLabel("Please wait, locating revisions...") , BorderLayout.CENTER );
		dialog.pack();
		dialog.setVisible(true);

		final IProgressCallback callback = new IProgressCallback() {

			@Override
			public void foundCommit(ObjectId commitId) {
				System.out.println("*** Found commit "+commitId);
			}
		};

		System.out.println("Locating commits...");
		commitList = gitHelper.findCommits( file , callback );

		dialog.setVisible(false);

		if ( commitList.isEmpty() ) {
			throw new RuntimeException("Found no commits");
		}		
		setMenuBar( createMenuBar() );

		diffModeChooser.setModel( new DefaultComboBoxModel<MyFrame.DiffDisplayMode>( DiffDisplayMode.values() ) );
		diffModeChooser.setSelectedItem( DiffDisplayMode.ALIGN_CHANGES );
		diffModeChooser.addActionListener( new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) 
			{
				final ObjectId commit = commitList.getCommit(revisionSlider.getValue()-1);
				try {
					diffPanel.showRevision( commit );
				} catch (IOException | PatchApplyException e1) {
					e1.printStackTrace();
				}				
			}
		});

		diffModeChooser.setRenderer( new DefaultListCellRenderer()  {

			@Override
			public Component getListCellRendererComponent(JList<?> list,
					Object value, int index, boolean isSelected,
					boolean cellHasFocus) 
			{
				Component result = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				DiffDisplayMode mode = (DiffDisplayMode) value;
				switch( mode ) {
				case ALIGN_CHANGES:
					setText( "Align changes" );
					break;
				case REGULAR:
					setText( "Regular" );
					break;
				default:
					setText( mode.toString() );
				}
				return result;
			}
		});

		revisionSlider = new JSlider( 1 , commitList.size() );

		revisionSlider.setPaintLabels(true);
		revisionSlider.setPaintTicks(true);

		addKeyListener( keyListener );
		getContentPane().addKeyListener( keyListener );

		if ( commitList.size() < 10 ) {
			revisionSlider.setMajorTickSpacing(1);
			revisionSlider.setMinorTickSpacing(1);
		} else {
			revisionSlider.setMajorTickSpacing(5);
			revisionSlider.setMinorTickSpacing(1);
		}

		final ObjectId latestCommit = commitList.getLatestCommit();
		if ( latestCommit != null ) {
			revisionSlider.setValue( 1 + commitList.indexOf( latestCommit ) );
			revisionSlider.setToolTipText( latestCommit.getName() );
		}

		revisionSlider.addChangeListener( new ChangeListener() {

			@Override
			public void stateChanged(ChangeEvent e) 
			{
				if ( ! revisionSlider.getValueIsAdjusting() ) 
				{
					final ObjectId commit = commitList.getCommit(revisionSlider.getValue()-1);
					long time = -System.currentTimeMillis();
					try {
						diffPanel.showRevision( commit );
					} catch (IOException | PatchApplyException e1) {
						e1.printStackTrace();
					} finally {
						time += System.currentTimeMillis();
					}
					if ( Main.DEBUG_MODE ) {
						System.out.println("Rendering time: "+time);
					}
				}
			}
		});

		getContentPane().setLayout( new GridBagLayout() );

		GridBagConstraints cnstrs = new GridBagConstraints();
		cnstrs.gridx=0 ; cnstrs.gridy=0;
		cnstrs.gridwidth=1; cnstrs.gridheight=1;
		cnstrs.weightx=0; cnstrs.weighty=0;
		cnstrs.fill = GridBagConstraints.NONE;

		getContentPane().add( new JLabel("Diff display mode:"), cnstrs );		

		cnstrs = new GridBagConstraints();
		cnstrs.gridx=1 ; cnstrs.gridy=0;
		cnstrs.gridwidth=1; cnstrs.gridheight=1;
		cnstrs.weightx=0; cnstrs.weighty=0;
		cnstrs.fill = GridBagConstraints.NONE;

		getContentPane().add( diffModeChooser , cnstrs );

		cnstrs = new GridBagConstraints();
		cnstrs.gridx=2 ; cnstrs.gridy=0;
		cnstrs.gridwidth=1; cnstrs.gridheight=1;
		cnstrs.weightx=1.0; cnstrs.weighty=0;
		cnstrs.fill = GridBagConstraints.HORIZONTAL;		

		getContentPane().add( revisionSlider , cnstrs );

		cnstrs = new GridBagConstraints();
		cnstrs.gridx=0 ; cnstrs.gridy=1;
		cnstrs.gridwidth=3; cnstrs.gridheight=1;
		cnstrs.weightx=1; cnstrs.weighty=1;
		cnstrs.fill = GridBagConstraints.BOTH;			

		getContentPane().add( diffPanel , cnstrs );

		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);		

		if ( latestCommit != null ) {
			diffPanel.showRevision( latestCommit );
		}
	}

	private MenuBar createMenuBar() {
		final MenuBar menuBar = new MenuBar();
		final Menu menu = new Menu("File");

		final MenuItem item1 = new MenuItem("About...");
		item1.addActionListener( new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) 
			{
				JOptionPane.showMessageDialog( null , "GIT timelapse V0.1\n\n(C) 2014 tobias.gierke@code-sourcery.de" , "About" , JOptionPane.PLAIN_MESSAGE);
			}
		});

		menu.add( item1 );

		final MenuItem item2 = new MenuItem("Quit");
		item2.addActionListener( new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) 
			{
				System.exit(0);
			}
		});

		menu.add( item2 );		
		menuBar.add( menu );
		return menuBar;
	}

	protected final static SimpleAttributeSet createStyle(Color color) 
	{
		SimpleAttributeSet result = new SimpleAttributeSet();
		StyleConstants.setBackground( result , color );
		return result;
	}

	protected final class DiffPanel extends JPanel 
	{
		private final JTextPane currentRevisionText = new JTextPane();
		private final JTextPane previousRevisionText = new JTextPane();

		private final JTextArea currentRevisionInfo = new JTextArea();
		private final JTextArea previousRevisionInfo  = new JTextArea();

		private final SimpleAttributeSet deletedLineStyle;
		private final SimpleAttributeSet addedLineStyle;

		private final JScrollPane leftScrollPane;		
		private final JScrollPane rightScrollPane;

		private final Map<Integer,LineOffsets> lineNumberToOffsets = new HashMap<>();		
		private int currentLineNumber = -1;		
		
		private TextLineNumber previousLineNumbersComponent;
		private TextLineNumber currentLineNumbersComponent;

		public DiffPanel() 
		{
			deletedLineStyle = createStyle( Color.RED );
			addedLineStyle = createStyle( Color.GREEN );

			final Font font = new Font(Font.MONOSPACED, Font.PLAIN, 12);

			currentRevisionInfo.addKeyListener( keyListener );
			previousRevisionInfo.addKeyListener( keyListener );

			currentRevisionInfo.setFont(font);
			previousRevisionInfo.setFont(font);
			currentRevisionText.setFont(font);
			previousRevisionText.setFont(font);

			currentRevisionInfo.setRows( 5 );
			currentRevisionInfo.setColumns( 40 );
			currentRevisionInfo.setEditable(false);

			previousRevisionInfo.setRows( 5 );
			previousRevisionInfo.setColumns( 40 );
			previousRevisionInfo.setEditable(false);			

			currentRevisionText.setEditable(false);
			previousRevisionText.setEditable(false);
			
			previousLineNumbersComponent = new TextLineNumber(previousRevisionText,4);
			currentLineNumbersComponent = new TextLineNumber(currentRevisionText,4);				

			setLayout(new BorderLayout() );

			// setup left panel
			final JPanel leftPanel = new JPanel();
			leftPanel.setLayout( new BorderLayout() );
			leftPanel.add( previousRevisionInfo , BorderLayout.NORTH);
			
			leftScrollPane = new JScrollPane( previousRevisionText );
			
			final JPanel leftLineNumAndText  = createTextComponentWithLineNumbers(previousLineNumbersComponent,leftScrollPane);
			leftPanel.add( leftLineNumAndText , BorderLayout.CENTER);

			// setup right panel
			final JPanel rightPanel = new JPanel();
			rightPanel.setLayout( new BorderLayout() );
			rightPanel.add( currentRevisionInfo , BorderLayout.NORTH);
			
			rightScrollPane = new JScrollPane( currentRevisionText );
			
			final JPanel rightLineNumAndText  = createTextComponentWithLineNumbers(currentLineNumbersComponent,rightScrollPane);			
			rightPanel.add( rightLineNumAndText , BorderLayout.CENTER);	

			// link scroll bars
			leftScrollPane.getHorizontalScrollBar().setModel(rightScrollPane.getHorizontalScrollBar().getModel());
			final JScrollBar verticalScrollbar = rightScrollPane.getVerticalScrollBar();

			verticalScrollbar.addAdjustmentListener( new AdjustmentListener() {

				@Override
				public void adjustmentValueChanged(AdjustmentEvent e) 
				{
					if ( adjustmentListenerActive && ! e.getValueIsAdjusting() ) 
					{
						rememberCaretPosition();
					}
				}
			});

			final BoundedRangeModel verticalScrollbarModel = verticalScrollbar.getModel();
			leftScrollPane.getVerticalScrollBar().setModel(verticalScrollbarModel);

			final JPanel compoundPanel = new JPanel();
			compoundPanel.setLayout( new GridBagLayout() );

			GridBagConstraints cnstrs = new GridBagConstraints();
			cnstrs.gridx=0 ; cnstrs.gridy=0;
			cnstrs.gridwidth=1; cnstrs.gridheight=1;
			cnstrs.weightx=0.5; cnstrs.weighty=0.2;
			cnstrs.fill = GridBagConstraints.BOTH;
			compoundPanel.add( new JScrollPane( previousRevisionInfo ) , cnstrs );			

			cnstrs = new GridBagConstraints();
			cnstrs.gridx=0 ; cnstrs.gridy=1;
			cnstrs.gridwidth=1; cnstrs.gridheight=1;
			cnstrs.weightx=0.5; cnstrs.weighty=0.8;
			cnstrs.fill = GridBagConstraints.BOTH;
			compoundPanel.add( leftLineNumAndText , cnstrs );

			// right panel
			cnstrs = new GridBagConstraints();
			cnstrs.gridx=1 ; cnstrs.gridy=0;
			cnstrs.gridwidth=1; cnstrs.gridheight=1;
			cnstrs.weightx=0.5; cnstrs.weighty=0.2;
			cnstrs.fill = GridBagConstraints.BOTH;
			compoundPanel.add( new JScrollPane( currentRevisionInfo ) , cnstrs );			

			cnstrs = new GridBagConstraints();
			cnstrs.gridx=1 ; cnstrs.gridy=1;
			cnstrs.gridwidth=1; cnstrs.gridheight=1;
			cnstrs.weightx=0.5; cnstrs.weighty=0.8;
			cnstrs.fill = GridBagConstraints.BOTH;
			compoundPanel.add( rightScrollPane , cnstrs );

			add( compoundPanel , BorderLayout.CENTER );
		}
		
		private JPanel createTextComponentWithLineNumbers(TextLineNumber tl,JScrollPane scrollPane) 
		{
			final JPanel lineNumbersAndText = new JPanel();
			lineNumbersAndText.setLayout( new GridBagLayout() );
			
			GridBagConstraints cnstrs = new GridBagConstraints();
			cnstrs.gridx=0 ; cnstrs.gridy=0;
			cnstrs.gridwidth=1; cnstrs.gridheight=1;
			cnstrs.weightx=0; cnstrs.weighty=1;
			cnstrs.fill = GridBagConstraints.VERTICAL;			
			
			lineNumbersAndText.add( tl , cnstrs );
			
			cnstrs = new GridBagConstraints();
			cnstrs.gridx=1 ; cnstrs.gridy=0;
			cnstrs.gridwidth=1; cnstrs.gridheight=1;
			cnstrs.weightx=1; cnstrs.weighty=1;
			cnstrs.fill = GridBagConstraints.BOTH;				
			
			scrollPane.setRowHeaderView( tl );
			lineNumbersAndText.add( scrollPane , cnstrs );
			return lineNumbersAndText;
		}

		public void showRevision(ObjectId current) throws IOException, PatchApplyException {
			showRevisions(commitList.getPredecessor(current),current);
		}

		private void rememberCaretPosition() 
		{
			final int leftCaretPosition = previousRevisionText.viewToModel( getPoint( leftScrollPane ) );
			final int rightCaretPosition = currentRevisionText.viewToModel( getPoint( rightScrollPane ) );			
			int leftLine = -1;
			if ( leftCaretPosition >= 0 ) {
				leftLine = previousRevisionText.getDocument().getDefaultRootElement().getElementIndex( leftCaretPosition );
				previousRevisionText.setCaretPosition( leftCaretPosition );				
			}
			if ( rightCaretPosition >= 0 ) 
			{
				currentLineNumber= currentRevisionText.getDocument().getDefaultRootElement().getElementIndex( rightCaretPosition );
				currentRevisionText.setCaretPosition( rightCaretPosition );
			}			
			if ( Main.DEBUG_MODE) {
				System.out.println("Right Caret position "+rightCaretPosition+" => line "+currentLineNumber);
				System.out.println("Left Caret position "+leftCaretPosition+" => line "+leftLine);
			}
		}
		
		private Point getPoint(JScrollPane pane) {
			JViewport rightViewport = (JViewport) rightScrollPane.getViewport();
			Rectangle rightViewRect = rightViewport.getViewRect();
			return rightViewRect.getLocation();
		}

		public final void restoreCaretPosition()
		{
			if ( currentLineNumber < 0 ) {
				return;
			}

			final int caretPosition = lineNumberToOffset( currentLineNumber );
			if ( Main.DEBUG_MODE ) {
				System.out.println("restoreCaretPosition(): Line "+currentLineNumber+" => offset "+caretPosition);
			}
			if ( caretPosition < 0 ) {
				return;
			}

			final Runnable r = new Runnable() 
			{
				@Override
				public void run() 
				{
					currentRevisionText.setCaretPosition( caretPosition );
				}
			};

			if ( SwingUtilities.isEventDispatchThread() ) 
			{
				r.run();
			} 
			else 
			{
				try {
					SwingUtilities.invokeAndWait( r );
				} 
				catch (InvocationTargetException | InterruptedException e) { /* can't help it */ }
			}
		}		

		public void showRevisions(ObjectId previous,ObjectId current) throws IOException, PatchApplyException 
		{
			populateCommitInfo( previousRevisionInfo , previous );
			populateCommitInfo( currentRevisionInfo , current );

			final byte[] currentFile = commitList.readFile( current );

			rememberCaretPosition();

			adjustmentListenerActive = false; // disable scrollbar adjustment listener so we don't overwrite the caret position we just remembered 
			try 
			{
				if ( previous == null ) {
					final byte[] currentRev = current != null ? currentFile : new byte[0];
					setCurrentText( toString( currentRev ) );
					previousRevisionText.setText( "" );		
					restoreCaretPosition();
					return;
				}

				if ( diffModeChooser.getSelectedItem() == DiffDisplayMode.REGULAR ) 
				{
					final byte[] diff = diff(previous,current);

					final RawText previousText = new RawText( commitList.readFile( previous ) );
					TextFile tf = new TextFile( previousText );

					previousRevisionText.setText( new TextFile( previousText ).toString() );

					Patch patch = new Patch();
					patch.parse( new ByteArrayInputStream(diff ) );				
					tf.forwardsPatch( patch );
					highlightText(previousRevisionText, tf );

					tf = new TextFile( previousText );

					patch = new Patch();
					patch.parse( new ByteArrayInputStream(diff ) );
					tf.backwardsPatch( patch );
					setCurrentText( tf.toString() );
					highlightText(currentRevisionText, tf );

					restoreCaretPosition();				
					return;
				}

				// old text
				final RawText previousFile = new RawText( commitList.readFile( previous ) );		
				final byte[] diff = diff(previous,current);

				final TextFile textAndAttributes = generateDiff( diff , previousFile , current ) ;
				previousRevisionText.setText( textAndAttributes.toString() );

				highlightText(previousRevisionText,textAndAttributes);

				// new text

				final TextFile tf = new TextFile( new RawText( currentFile ) );
				final Patch patch = new Patch();
				patch.parse( new ByteArrayInputStream( diff( current , previous ) ) );
				tf.backwardsPatchAndAlign( patch );

				setCurrentText( tf.toString() );
				highlightText( currentRevisionText , tf );

				restoreCaretPosition();
			} finally {
				adjustmentListenerActive = true;
			}
		}

		private int lineNumberToOffset(int lineNumber) 
		{
			for ( LineOffsets l : lineNumberToOffsets.values() ) {
				if ( l.lineNumber == lineNumber ) {
					return l.start;
				}
			}
			return -1;		
		}

		/**
		 * 
		 * @param text
		 * @param offset
		 * @return line number (first line has number 0)  or -1 if offset is out-of-bounds
		 */
		private int offsetToLineNumber(int offset) 
		{
			for ( LineOffsets l : lineNumberToOffsets.values() ) {
				if ( l.containsOffset( offset ) ) {
					return l.lineNumber;
				}
			}
			return -1;
		}	

		protected void setCurrentText(String newText) 
		{
			this.lineNumberToOffsets.clear();

			int offset1 = 0;
			int offset2 = 0;
			int currentLine = 0;
			final int len = newText.length();
			for ( int i = 0 ; i < len ; i++ ) 
			{
				final char c = newText.charAt(i);
				if ( c == '\n' ) {
					offset1 = offset2;
					offset2 = i;
					lineNumberToOffsets.put( currentLine , new LineOffsets(currentLine,offset1,offset2) );
					currentLine++;
				}
			}		
			currentRevisionText.setText( newText );		
		}

		private void highlightText(JTextPane editor , final TextFile textAndAttributes) throws IOException 
		{
			final Map<Integer, ChangeType> changedLines = textAndAttributes.getChangedLines();
			int start,end;
			for ( Entry<Integer, ChangeType> entry : changedLines.entrySet() ) 
			{
				try {
					switch( entry.getValue() ) 
					{
					case ADDED:
						start = textAndAttributes.getLineStartOffset( entry.getKey() );
						end = textAndAttributes.getLineEndOffset( entry.getKey() );
						editor.getStyledDocument().setCharacterAttributes( start , end - start, addedLineStyle , true );
						break;
					case DELETED:
						start = textAndAttributes.getLineStartOffset( entry.getKey() );
						end = textAndAttributes.getLineEndOffset( entry.getKey() );
						editor.getStyledDocument().setCharacterAttributes( start , end - start, deletedLineStyle , true );						
						break;
					default:
					}
				} catch(IndexOutOfBoundsException e) {
					System.err.println( e.getMessage() );
				}
			}
		}

		private byte[] diff(ObjectId previous,ObjectId current) throws IOException 
		{
			final ByteArrayOutputStream leftDiff = new ByteArrayOutputStream();

			final DiffFormatter formatter = new DiffFormatter( leftDiff );
			formatter.setRepository( gitHelper.getRepository() );
			formatter.setPathFilter( gitHelper.createPathFilter( file ) );
			formatter.format( previous, current );
			return leftDiff.toByteArray();
		}

		private TextFile generateDiff(byte[] diff,RawText previousFile, ObjectId current) throws IOException 
		{
			final Patch patch = new Patch();
			patch.parse( new ByteArrayInputStream( diff ) );

			try 
			{
				final TextFile tf = new TextFile(previousFile);
				tf.forwardsPatchAndAlign(  patch );
				return tf;
			}
			catch (PatchApplyException e) {
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		}

		private void populateCommitInfo(final JTextArea area,ObjectId commit) 
		{
			if ( commit != null ) 
			{
				final ICommitVisitor visitor = new ICommitVisitor() 
				{
					@Override
					public boolean visit(RevCommit commit) throws IOException 
					{
						final StringBuilder builder = new StringBuilder();
						builder.append( line("Commit" , commit.getId().getName() ) );

						final long millis =  commit.getCommitTime()*1000;
						builder.append( line("Date" , new Date(millis).toString() ) );

						final PersonIdent authorIdent = commit.getAuthorIdent();
						builder.append( line("Author" , authorIdent.getEmailAddress() ) );

						final PersonIdent committerIdent = commit.getCommitterIdent();
						builder.append( line("Committer" , committerIdent.getEmailAddress() ) );						

						area.setText( builder.toString() );
						return false;
					}
				};

				try {
					commitList.visitCommit( commit , visitor );
				} 
				catch (IOException e) 
				{
					e.printStackTrace();
					area.setText( "ERROR: "+e.getMessage());
				}
			} else {
				area.setText("");
			}
		}

		private String line(String key,String value) {
			return pad(key,10)+": "+value+"\n";
		}

		private String pad(String s,int len) {
			return StringUtils.rightPad(s, len);
		}

		private String toString(byte[] blob) 
		{
			final RawText text = new RawText( blob );
			return text.getString( 0 , text.size() , true );
		}
	}	
}