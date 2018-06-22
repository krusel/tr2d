/**
 *
 */
package com.indago.tr2d.ui.view;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import com.indago.tr2d.ui.model.Tr2dSolDiffModel;
import com.indago.tr2d.ui.model.Tr2dTrackingModel;
import com.indago.tr2d.ui.view.bdv.overlays.Tr2dFlowOverlay;
import com.indago.tr2d.ui.view.bdv.overlays.Tr2dTrackingOverlay;

import bdv.util.Bdv;
import bdv.util.BdvHandlePanel;
import indago.ui.progress.DialogProgress;
import net.imglib2.type.numeric.ARGBType;
import net.miginfocom.swing.MigLayout;

/**
 * @author jug
 */
public class Tr2dTrackingPanel extends JPanel implements ActionListener, FocusListener, ChangeListener {

	private static final long serialVersionUID = -500536787731292765L;

	private final Tr2dTrackingModel model;

	private JTabbedPane tabs;
	private JButton bRun;
	private JButton bRestart;
	private JButton bRestartFG;
	private JButton bRefetch;

	private Tr2dFrameEditPanel frameEditPanel;

	private DialogProgress trackingProgressDialog;

	private JTextField txtMaxMovementsPerNode;
	private JTextField txtMaxDivisionsPerNode;
	private JTextField txtMaxMovementSearchRadius;
	private JTextField txtMaxDivisionSearchRadius;
	
	private JTextField txtNumberDiverseSolutions;
	private JButton bPrevSolution;
	private JLabel lCurrentSolution;
	private int currentDiverseSolutionIndex;
	private JButton bNextSolution;
	
	private JTextField txtDiverseSegmentCost;
	private JTextField txtDiverseAppearanceCost;
	private JTextField txtDiverseDisappearanceCost;
	private JTextField txtDiverseMovementCost;
	private JTextField txtDiverseDivisionCost;

	public Tr2dTrackingPanel( final Tr2dTrackingModel trackingModel ) {
		super( new BorderLayout() );
		this.model = trackingModel;
		model.addStateChangedListener( this );

		buildGui();

		model.bdvAdd( model.getTr2dModel().getRawData(), "RAW" );
		if ( model.getImgSolution() != null ) {
			model.bdvAdd( model.getImgSolution(), "solution", 0, 5, new ARGBType( 0xFF0000 ), true );
			model.getDiverseSolutionBdvSources().addAll( model.bdvGetSources().subList( 1, model.bdvGetSources().size() ) );
		}
//		model.bdvAdd( new Tr2dTrackingOverlay( model ), "overlay_tracking", true );
		model.bdvAdd( new Tr2dFlowOverlay( model.getTr2dModel().getFlowModel() ), "overlay_flow", false );

		trackingProgressDialog = null;
	}

	/**
	 * Builds the GUI of this panel.
	 */
	private void buildGui() {
		tabs = new JTabbedPane( JTabbedPane.TOP );
		tabs.add( "tracker", buildSolverPanel() );
		tabs.add( "frame editor", buildFrameEditPanel() );
		tabs.add( "cost editor", buildCostEditorPanel() );

		this.add( tabs, BorderLayout.CENTER );
	}

	private Component buildFrameEditPanel() {
		frameEditPanel = new Tr2dFrameEditPanel( model );
		return frameEditPanel;
	}

	private Component buildCostEditorPanel() {
		final JPanel panel = new Tr2dCostEditorPanel( model, new Tr2dSolDiffModel( model ) );
		return panel;
	}

	private JPanel buildSolverPanel() {
		final JPanel panel = new JPanel( new BorderLayout() );

		final JPanel controls = new JPanel( new MigLayout() );
		final JPanel viewer = new JPanel( new BorderLayout() );

		final JPanel panelGraphConstructionParams = new JPanel( new MigLayout() );
		txtMaxMovementSearchRadius = new JTextField( "" + model.getMaxMovementSearchRadius(), 3 );
		txtMaxMovementSearchRadius.addActionListener( this );
		txtMaxMovementSearchRadius.addFocusListener( this );
		txtMaxMovementsPerNode = new JTextField( "" + model.getMaxMovementsToAddPerHypothesis(), 3 );
		txtMaxMovementsPerNode.addActionListener( this );
		txtMaxMovementsPerNode.addFocusListener( this );
		txtMaxDivisionSearchRadius = new JTextField( "" + model.getMaxDivisionSearchRadius(), 3 );
		txtMaxDivisionSearchRadius.addActionListener( this );
		txtMaxDivisionSearchRadius.addFocusListener( this );
		txtMaxDivisionsPerNode = new JTextField( "" + model.getMaxDivisionsToAddPerHypothesis(), 3 );
		txtMaxDivisionsPerNode.addActionListener( this );
		txtMaxDivisionsPerNode.addFocusListener( this );

		panelGraphConstructionParams.setBorder( BorderFactory.createTitledBorder( "graph parameters" ) );
		panelGraphConstructionParams.add( new JLabel( "Max move radius:" ), "growx" );
		panelGraphConstructionParams.add( txtMaxMovementSearchRadius, "growx, wrap" );
		panelGraphConstructionParams.add( new JLabel( "Max move assmts:" ), "growx" );
		panelGraphConstructionParams.add( txtMaxMovementsPerNode, "growx, wrap" );
		panelGraphConstructionParams.add( new JLabel( "Max division radius:" ), "growx" );
		panelGraphConstructionParams.add( txtMaxDivisionSearchRadius, "growx, wrap" );
		panelGraphConstructionParams.add( new JLabel( "Max division assmts:" ), "growx" );
		panelGraphConstructionParams.add( txtMaxDivisionsPerNode, "growx, wrap" );

		final JPanel panelDiversityParams = new JPanel( new MigLayout() );
		txtNumberDiverseSolutions = new JTextField( "" + model.getNumberDiverseSolutions(), 3 );
		txtNumberDiverseSolutions.addActionListener( this );
		txtNumberDiverseSolutions.addFocusListener( this );
		bPrevSolution = new JButton( "<" );
		bPrevSolution.addActionListener( this );
		currentDiverseSolutionIndex = 0;
		lCurrentSolution = new JLabel( "Solution " + ( currentDiverseSolutionIndex + 1 ) + "/1" );
		bNextSolution = new JButton( ">" );
		bNextSolution.addActionListener( this );

		panelDiversityParams.setBorder( BorderFactory.createTitledBorder( "diverse solutions" ) );
		panelDiversityParams.add( new JLabel( "Number of Solutions:" ), "growx" );
		panelDiversityParams.add( txtNumberDiverseSolutions, "growx, wrap" );
		JPanel panelCycleThroughSolutions = new JPanel();
		panelCycleThroughSolutions.add( bPrevSolution, "" );
		panelCycleThroughSolutions.add( lCurrentSolution, "" );
		panelCycleThroughSolutions.add( bNextSolution, "" );
		panelDiversityParams.add( panelCycleThroughSolutions, "span, wrap" );

		final JPanel panelDiversityCostParams = new JPanel( new MigLayout() );
		txtDiverseSegmentCost = new JTextField( "" + model.getDiverseSegmentCost(), 5 );
		txtDiverseSegmentCost.addActionListener( this );
		txtDiverseSegmentCost.addFocusListener( this );
		txtDiverseAppearanceCost = new JTextField( "" + model.getDiverseAppearanceCost(), 5 );
		txtDiverseAppearanceCost.addActionListener( this );
		txtDiverseAppearanceCost.addFocusListener( this );
		txtDiverseDisappearanceCost = new JTextField( "" + model.getDiverseDisappearanceCost(), 5 );
		txtDiverseDisappearanceCost.addActionListener( this );
		txtDiverseDisappearanceCost.addFocusListener( this );
		txtDiverseMovementCost = new JTextField( "" + model.getDiverseMovementCost(), 5 );
		txtDiverseMovementCost.addActionListener( this );
		txtDiverseMovementCost.addFocusListener( this );
		txtDiverseDivisionCost = new JTextField( "" + model.getDiverseDivisionCost(), 5 );
		txtDiverseDivisionCost.addActionListener( this );
		txtDiverseDivisionCost.addFocusListener( this );
		
		panelDiversityCostParams.setBorder( BorderFactory.createTitledBorder( "diversity parameters" ) );
		panelDiversityCostParams.add( new JLabel( "Segments:" ), "growx" );
		panelDiversityCostParams.add( txtDiverseSegmentCost, "growx, wrap" );
		panelDiversityCostParams.add( new JLabel( "Appearance:" ), "growx" );
		panelDiversityCostParams.add( txtDiverseAppearanceCost, "growx, wrap" );
		panelDiversityCostParams.add( new JLabel( "Disappearance:" ), "growx" );
		panelDiversityCostParams.add( txtDiverseDisappearanceCost, "growx, wrap" );
		panelDiversityCostParams.add( new JLabel( "Movement:" ), "growx" );
		panelDiversityCostParams.add( txtDiverseMovementCost, "growx, wrap" );
		panelDiversityCostParams.add( new JLabel( "Divisions:" ), "growx" );
		panelDiversityCostParams.add( txtDiverseDivisionCost, "growx, wrap" );
		
		bRun = new JButton( "track" );
		bRun.addActionListener( this );
		bRestart = new JButton( "restart" );
		bRestart.addActionListener( this );
		bRestartFG = new JButton( "restart FG only" );
		bRestartFG.addActionListener( this );
		bRefetch = new JButton( "fetch & track" );
		bRefetch.addActionListener( this );

		model.bdvSetHandlePanel(
				new BdvHandlePanel( ( Frame ) this.getTopLevelAncestor(), Bdv
						.options()
						.is2D()
						.inputTriggerConfig( model.getTr2dModel().getDefaultInputTriggerConfig() ) ) );

		controls.add( panelGraphConstructionParams, "growx, wrap" );
		controls.add( panelDiversityParams, "growx, wrap" );
		controls.add( panelDiversityCostParams, "growx, wrap" );

		controls.add( bRun, "growx, wrap" );
		controls.add( bRestart, "growx, wrap" );
		controls.add( bRestartFG, "growx, wrap" );
		controls.add( bRefetch, "growx, wrap" );
		viewer.add( model.bdvGetHandlePanel().getViewerPanel(), BorderLayout.CENTER );

		final JSplitPane splitPane = new JSplitPane( JSplitPane.HORIZONTAL_SPLIT, controls, viewer );
		panel.add( splitPane, BorderLayout.CENTER );

		return panel;
	}

	/**
	 * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
	 */
	@Override
	public void actionPerformed( final ActionEvent e ) {
		if ( trackingProgressDialog == null ) {
			trackingProgressDialog = new DialogProgress( this, "Starting tracking...", 10 );
			model.addProgressListener( trackingProgressDialog );
		}

		if ( e.getSource().equals( bRun ) ) {
			model.runInThread( false );
			currentDiverseSolutionIndex = 0;
			switchToSolution( currentDiverseSolutionIndex );
		} else if ( e.getSource().equals( bRestart ) ) {
			this.frameEditPanel.emptyUndoRedoStacks();
			model.runInThread( true, true );
			currentDiverseSolutionIndex = 0;
			switchToSolution( currentDiverseSolutionIndex );
		} else if ( e.getSource().equals( bRestartFG ) ) {
			this.frameEditPanel.emptyUndoRedoStacks();
			model.runInThread( true, false );
			currentDiverseSolutionIndex = 0;
			switchToSolution( currentDiverseSolutionIndex );
		} else if ( e.getSource().equals( bRefetch ) ) {
			final Thread t = new Thread( new Runnable() {

				@Override
				public void run() {
					model.reset();
					model.runInThread( true );
				}
			} );
			t.start();
			currentDiverseSolutionIndex = 0;
			switchToSolution( currentDiverseSolutionIndex );
		} else if ( e.getSource().equals( bPrevSolution ) ) {
			currentDiverseSolutionIndex = ( currentDiverseSolutionIndex - 1 + model.getDiverseSolutionBdvSources().size() ) % model.getDiverseSolutionBdvSources().size();
			switchToSolution( currentDiverseSolutionIndex );
		} else if ( e.getSource().equals( bNextSolution ) ) {
			currentDiverseSolutionIndex = ( currentDiverseSolutionIndex + 1 ) % model.getDiverseSolutionBdvSources().size();
			switchToSolution( currentDiverseSolutionIndex );
		}
	}

	/**
	 * @see java.awt.event.FocusListener#focusGained(java.awt.event.FocusEvent)
	 */
	@Override
	public void focusGained( final FocusEvent e ) {}

	/**
	 * @see java.awt.event.FocusListener#focusLost(java.awt.event.FocusEvent)
	 */
	@Override
	public void focusLost( final FocusEvent e ) {
		if ( e.getSource().equals( txtMaxMovementSearchRadius ) ||
			 e.getSource().equals( txtMaxMovementsPerNode ) ||
			 e.getSource().equals( txtMaxDivisionSearchRadius ) ||
			 e.getSource().equals( txtMaxDivisionsPerNode ) ) {
			parseAndSetGraphParametersInModel();
			model.saveStateToFile();
		}
		if ( e.getSource().equals( txtDiverseSegmentCost ) ||
			 e.getSource().equals( txtDiverseAppearanceCost ) ||
			 e.getSource().equals( txtDiverseDisappearanceCost ) ||
			 e.getSource().equals( txtDiverseMovementCost ) ||
			 e.getSource().equals( txtDiverseDivisionCost ) ) {
			parseAndSetDivsersityCostsInModel();
		}
		if ( e.getSource().equals( txtNumberDiverseSolutions ) ) {
			try {
				model.setNumberDiverseSolutions( Integer.parseInt( txtNumberDiverseSolutions.getText() ) );
				if ( model.getNumberDiverseSolutions() < 1 ) {
					model.setNumberDiverseSolutions( 1 );
					txtNumberDiverseSolutions.setText( "1" );
				}
			} catch ( final NumberFormatException exc ) {
				txtNumberDiverseSolutions.setText( "" + model.getNumberDiverseSolutions() );
			}
		}
	}

	/**
	 *
	 */
	private void parseAndSetGraphParametersInModel() {
		try {
			model.setMaxMovementSearchRadius( Integer.parseInt( txtMaxMovementSearchRadius.getText() ) );
		} catch ( final NumberFormatException e ) {
			txtMaxMovementSearchRadius.setText( "" + model.getMaxMovementSearchRadius() );
		}
		try {
			model.setMaxMovementsToAddPerHypothesis( Integer.parseInt( txtMaxMovementsPerNode.getText() ) );
		} catch ( final NumberFormatException e ) {
			txtMaxMovementsPerNode.setText( "" + model.getMaxMovementsToAddPerHypothesis() );
		}
		try {
			model.setMaxDivisionSearchRadius( Integer.parseInt( txtMaxDivisionSearchRadius.getText() ) );
		} catch ( final NumberFormatException e ) {
			txtMaxDivisionSearchRadius.setText( "" + model.getMaxDivisionSearchRadius() );
		}
		try {
			model.setMaxDivisionsToAddPerHypothesis( Integer.parseInt( txtMaxDivisionsPerNode.getText() ) );
		} catch ( final NumberFormatException e ) {
			txtMaxDivisionsPerNode.setText( "" + model.getMaxDivisionsToAddPerHypothesis() );
		}
	}

	private void parseAndSetDivsersityCostsInModel() {
		try {
			model.setDiverseSegmentCost( Double.parseDouble( txtDiverseSegmentCost.getText() ) );
			// System.out.println( "changed diverse segment cost" );
		} catch ( final NumberFormatException e ) {
			txtDiverseSegmentCost.setText( "" + model.getDiverseSegmentCost() );
		}
		try {
			model.setDiverseAppearanceCost( Double.parseDouble( txtDiverseAppearanceCost.getText() ) );
		} catch ( final NumberFormatException e ) {
			txtDiverseAppearanceCost.setText( "" + model.getDiverseAppearanceCost() );
		}
		try {
			model.setDiverseDisappearanceCost( Double.parseDouble( txtDiverseDisappearanceCost.getText() ) );
		} catch ( final NumberFormatException e ) {
			txtDiverseDisappearanceCost.setText( "" + model.getDiverseDisappearanceCost() );
		}
		try {
			model.setDiverseMovementCost( Double.parseDouble( txtDiverseMovementCost.getText() ) );
		} catch ( final NumberFormatException e ) {
			txtDiverseMovementCost.setText( "" + model.getDiverseMovementCost() );
		}
		try {
			model.setDiverseDivisionCost( Double.parseDouble( txtDiverseDivisionCost.getText() ) );
		} catch ( final NumberFormatException e ) {
			txtDiverseDivisionCost.setText( "" + model.getDiverseDivisionCost() );
		}
	}

	/**
	 * @see javax.swing.event.ChangeListener#stateChanged(javax.swing.event.ChangeEvent)
	 */
	@Override
	public void stateChanged( final ChangeEvent e ) {
		this.txtMaxMovementSearchRadius.setText( "" + model.getMaxMovementSearchRadius() );
		this.txtMaxMovementsPerNode.setText( "" + model.getMaxMovementsToAddPerHypothesis() );
		this.txtMaxDivisionSearchRadius.setText( "" + model.getMaxDivisionSearchRadius() );
		this.txtMaxDivisionsPerNode.setText( "" + model.getMaxDivisionsToAddPerHypothesis() );
		this.txtDiverseSegmentCost.setText( "" + model.getDiverseSegmentCost() );
		this.txtDiverseAppearanceCost.setText( "" + model.getDiverseAppearanceCost() );
		this.txtDiverseDisappearanceCost.setText( "" + model.getDiverseDisappearanceCost() );
		this.txtDiverseMovementCost.setText( "" + model.getDiverseMovementCost() );
		this.txtDiverseDivisionCost.setText( "" + model.getDiverseDivisionCost() );
		this.txtNumberDiverseSolutions.setText( "" + model.getNumberDiverseSolutions() );
	}
	
	private void switchToSolution( int index ) {
		if (model.getTr2dTrackingOverlay() != null ) {
			model.getTr2dTrackingOverlay().setActiveSolution( index );	
		}
		else {
			index = 0;
		}
//		System.out.println( "current index: " + index );
		for( int i = 0; i < model.getDiverseSolutionBdvSources().size(); i++ ) {
			model.getDiverseSolutionBdvSources().get( i ).setActive( i == index );
//			System.out.println( model.getDiverseSolutionBdvSources().size() );
		}
		lCurrentSolution.setText( "Solution " + ( index + 1 ) + "/" + model.getDiverseSolutionBdvSources().size() );
	}
}
