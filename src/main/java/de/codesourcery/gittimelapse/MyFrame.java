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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuItem;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.PatchApplyException;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.patch.Patch;
import org.eclipse.jgit.revwalk.RevCommit;

import de.codesourcery.gittimelapse.GitHelper.ICommitVisitor;
import de.codesourcery.gittimelapse.TextFile.ChangeType;

public class MyFrame extends JFrame {

	private final File file;
	private final DiffPanel diffPanel;
	private final JSlider revisionSlider;
	private final GitHelper gitHelper;
	private final GitHelper.CommitList commitList;
	private final JComboBox<DiffDisplayMode> diffModeChooser = new JComboBox<>();

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

		commitList = gitHelper.findCommits( file );
		
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
					try {
						diffPanel.showRevision( commit );
					} catch (IOException | PatchApplyException e1) {
						e1.printStackTrace();
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
		
		private int caretPosition = -1;		

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

			setLayout(new BorderLayout() );

			final JPanel leftPanel = new JPanel();
			leftPanel.setLayout( new BorderLayout() );
			leftPanel.add( previousRevisionInfo , BorderLayout.NORTH);
			final JScrollPane leftPane = new JScrollPane( previousRevisionText );
			leftPanel.add( leftPane , BorderLayout.CENTER);

			final JPanel rightPanel = new JPanel();
			rightPanel.setLayout( new BorderLayout() );
			rightPanel.add( currentRevisionInfo , BorderLayout.NORTH);
			final JScrollPane rightPane = new JScrollPane( currentRevisionText );
			rightPanel.add( rightPane , BorderLayout.CENTER);	

			leftPane.getHorizontalScrollBar().setModel(rightPane.getHorizontalScrollBar().getModel());
			leftPane.getVerticalScrollBar().setModel(rightPane.getVerticalScrollBar().getModel());

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
			compoundPanel.add( leftPane , cnstrs );

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
			compoundPanel.add( rightPane , cnstrs );

			add( compoundPanel , BorderLayout.CENTER );
		}

		public void showRevision(ObjectId current) throws IOException, PatchApplyException {
			showRevisions(commitList.getPredecessor(current),current);
		}
		
		private void backupCaretPosition() {
			caretPosition = currentRevisionInfo.getCaretPosition();
		}
		
		private void restoreCaretPosition() 
		{
			if ( caretPosition != -1 ) {
				try {
					currentRevisionInfo.setCaretPosition( caretPosition );
				} catch(Exception e) {
					
				}
			}
		} 

		public void showRevisions(ObjectId previous,ObjectId current) throws IOException, PatchApplyException 
		{
			backupCaretPosition();
			
			populateCommitInfo( previousRevisionInfo , previous );
			populateCommitInfo( currentRevisionInfo , current );

			final byte[] currentFile = commitList.readFile( current );
			if ( previous == null ) {
				final byte[] currentRev = current != null ? currentFile : new byte[0];
				currentRevisionText.setText( toString( currentRev ) );
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
				currentRevisionText.setText( tf.toString() );
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

			currentRevisionText.setText( tf.toString() );
			highlightText( currentRevisionText , tf );
			
			restoreCaretPosition();
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