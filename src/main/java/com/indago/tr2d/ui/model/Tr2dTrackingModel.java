/**
 *
 */
package com.indago.tr2d.ui.model;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JOptionPane;
import javax.swing.event.ChangeListener;

import com.indago.costs.CostFactory;
import com.indago.data.segmentation.ConflictGraph;
import com.indago.data.segmentation.LabelingSegment;
import com.indago.fg.Assignment;
import com.indago.fg.AssignmentMapper;
import com.indago.fg.FactorGraphFactory;
import com.indago.fg.MappedDiverseFactorGraph;
import com.indago.fg.MappedFactorGraph;
import com.indago.fg.UnaryCostConstraintGraph;
import com.indago.fg.Variable;
import com.indago.ilp.DefaultLoggingGurobiCallback;
import com.indago.ilp.SolveGurobi;
import com.indago.io.IntTypeImgLoader;
import com.indago.io.ProjectFolder;
import com.indago.pg.IndicatorNode;
import com.indago.pg.assignments.AppearanceHypothesis;
import com.indago.pg.assignments.AssignmentNodes;
import com.indago.pg.assignments.DisappearanceHypothesis;
import com.indago.pg.assignments.DivisionHypothesis;
import com.indago.pg.assignments.MovementHypothesis;
import com.indago.pg.segments.SegmentNode;
import com.indago.tr2d.Tr2dContext;
import com.indago.tr2d.Tr2dLog;
import com.indago.tr2d.data.LabelingTimeLapse;
import com.indago.tr2d.io.projectfolder.Tr2dProjectFolder;
import com.indago.tr2d.pg.Tr2dSegmentationProblem;
import com.indago.tr2d.pg.Tr2dTrackingProblem;
import com.indago.tr2d.ui.listener.ModelInfeasibleListener;
import com.indago.tr2d.ui.listener.SolutionChangedListener;
import com.indago.tr2d.ui.util.SolutionVisulizer;
import com.indago.tr2d.ui.view.bdv.overlays.Tr2dTrackingOverlay;
import com.indago.ui.bdv.BdvWithOverlaysOwner;
import com.indago.util.TicToc;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;

import bdv.util.BdvHandlePanel;
import bdv.util.BdvOverlay;
import bdv.util.BdvSource;
import gurobi.GRBException;
import ij.IJ;
import indago.ui.progress.DialogProgress;
import indago.ui.progress.ProgressListener;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;


/**
 * @author jug
 */
public class Tr2dTrackingModel implements BdvWithOverlaysOwner {

	private final String FILENAME_STATE = "state.csv";
	private final ProjectFolder dataFolder;

	private final String FOLDER_LABELING_FRAMES = "labeling_frames";
	private ProjectFolder hypothesesFolder = null;

	private final String FILENAME_PGRAPH = "tracking.pgraph";
	private final String FILENAME_TRACKING = "tracking.tif";

	private final Tr2dModel tr2dModel;
	private final Tr2dSegmentationCollectionModel tr2dSegModel;

	private double maxMovementSearchRadius = 50;
	private double maxDivisionSearchRadius = 50;
	private int maxMovementsToAddPerHypothesis = 4;
	private int maxDivisionsToAddPerHypothesis = 8;
	
	private int numberDiverseSolutions = 1;

	private double diverseSegmentCost = 0.0;
	private double diverseAppearanceCost = 0.0;
	private double diverseDisappearanceCost = 0.0;
	private double diverseMovementCost = 0.0;
	private double diverseDivisionCost = 0.0;

	private final List< CostFactory< ? > > costFactories = new ArrayList<>();
	private final CostFactory< LabelingSegment > segmentCosts;
	private final CostFactory< LabelingSegment > appearanceCosts;
	private final CostFactory< Pair< Pair< LabelingSegment, LabelingSegment >, Pair< Double, Double > > > moveCosts;
	private final CostFactory< Pair< LabelingSegment, Pair< LabelingSegment, LabelingSegment > > > divisionCosts;
	private final CostFactory< LabelingSegment > disappearanceCosts;

	private Tr2dTrackingProblem tr2dTraProblem;
	private final LabelingTimeLapse labelingFrames;
	private RandomAccessibleInterval< IntType > imgSolution = null;

	private MappedFactorGraph mfg;
	private Assignment< Variable > fgSolution;
	private Assignment< IndicatorNode > pgSolution;

	private BdvHandlePanel bdvHandlePanel;
	private final List< RandomAccessibleInterval< IntType > > imgs;
	private final List< BdvSource > bdvSources = new ArrayList< >();
	private final List< BdvOverlay > overlays = new ArrayList< >();
	private final List< BdvSource > bdvOverlaySources = new ArrayList< >();

	private MappedDiverseFactorGraph dmfg;
	private List< RandomAccessibleInterval< IntType > > imgSolutionList = new ArrayList<>();
	private final List< BdvSource > diverseSolutionBdvSources = new ArrayList< >();
	private Tr2dTrackingOverlay trackingOverlay = null;
	private List< Assignment< IndicatorNode > > pgSolutionList = new ArrayList<>();

	private final List< SolutionChangedListener > solChangedListeners;
	private final List< ModelInfeasibleListener > modelInfeasibleListeners;

	private final List< ProgressListener > progressListeners = new ArrayList<>();

	private SolveGurobi solver;
	private final List< ChangeListener > stateChangedListeners;

	/**
	 * @param model
	 */
	public Tr2dTrackingModel(
			final Tr2dModel model,
			final CostFactory< LabelingSegment > segmentCosts,
			final CostFactory< LabelingSegment > appearanceCosts,
			final CostFactory< Pair< Pair< LabelingSegment, LabelingSegment >, Pair< Double, Double > > > movementCosts,
			final CostFactory< Pair< LabelingSegment, Pair< LabelingSegment, LabelingSegment > > > divisionCosts,
			final CostFactory< LabelingSegment > disappearanceCosts ) {
		this.tr2dModel = model;

		stateChangedListeners = new ArrayList<>();

		dataFolder = model.getProjectFolder().getFolder( Tr2dProjectFolder.TRACKING_FOLDER );
		dataFolder.mkdirs();

		solChangedListeners = new ArrayList<>();
		modelInfeasibleListeners = new ArrayList<>();

		this.segmentCosts = segmentCosts;
		this.appearanceCosts = appearanceCosts;
		this.moveCosts = movementCosts;
		this.divisionCosts = divisionCosts;
		this.disappearanceCosts = disappearanceCosts;

		getCostFactories().add( this.segmentCosts );
		getCostFactories().add( this.appearanceCosts );
		getCostFactories().add( this.disappearanceCosts );
		getCostFactories().add( this.moveCosts );
		getCostFactories().add( this.divisionCosts );

		this.tr2dSegModel = model.getSegmentationModel();

		final File fImgSol = dataFolder.addFile( FILENAME_TRACKING ).getFile();
		if ( fImgSol.canRead() ) {
			imgSolution = IntTypeImgLoader.loadTiffEnsureType( fImgSol );
		}
		imgs = new ArrayList< >();
		imgs.add( imgSolution );

		// Loading hypotheses labeling frames if exist in project folder
		this.labelingFrames = new LabelingTimeLapse( tr2dSegModel );
		try {
			hypothesesFolder = dataFolder.addFolder( FOLDER_LABELING_FRAMES );
			hypothesesFolder.loadFiles();
			labelingFrames.loadFromProjectFolder( hypothesesFolder );
		} catch ( final IOException ioe ) {
			ioe.printStackTrace();
		}

		loadStateFromProjectFolder();
	}

	/**
	 * (Re-)fetches all hypotheses and marks this tracking model as 'reset'.
	 */
	public void reset() {
		// purge segmentation data
		dataFolder.getFile( FILENAME_TRACKING ).getFile().delete();
		try {
			dataFolder.getFolder( FOLDER_LABELING_FRAMES ).deleteContent();
		} catch ( final IOException e ) {
			if ( dataFolder.getFolder( FOLDER_LABELING_FRAMES ).exists() ) {
				Tr2dLog.log.error( "Labeling frames could not be deleted." );
			}
		}
		// recollect segmentation data
		processSegmentationInputs( true );
		// purge problem graph
		tr2dTraProblem = null;
	}

	/**
	 * Prepares the tracking model (Step1: builds pg and stores intermediate
	 * data in
	 * project folder).0x00FF00
	 */
	private boolean preparePG() {
		if ( processSegmentationInputs( false ) ) {
			buildTrackingProblem();
			saveTrackingProblem();
			mfg = null;
			dmfg = null;
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Prepares the tracking model (Step2: builds fg from pg and stores
	 * intermediate data in
	 * project folder).
	 */
	public void prepareFG() {
		buildFactorGraph();
		saveFactorGraph();
	}

	/**
	 * Runs the optimization for the prepared tracking (in <code>prepare</code>
	 * was never called, this function will call it).
	 * Does not take care of the BDV.
	 * For a threaded version us <code>runInThread</code>, which also takes care
	 * of BDV.
	 * Does not force resolving. If wanted: call <code>run(true)</code>.
	 */
	public void run() {
		run( false, false );
	}

	/**
	 * Runs the optimization for the prepared tracking (in <code>prepare</code>
	 * was never called, this function will call it).
	 * Does not take care of the BDV.
	 * For a threaded version us <code>runInThread</code>, which also takes care
	 * of BDV.
	 *
	 * @param forceSolving
	 *            true, force resolve in any case.
	 * @param forceRebuildPG
	 *            true, force problem graph rebuild (drops all leveraged editing
	 *            constraints etc.)
	 */
	public void run( final boolean forceSolving, final boolean forceRebuildPG ) {
		boolean doSolving = forceSolving;

		if ( tr2dTraProblem == null || forceRebuildPG ) {
			if ( preparePG() ) {
				prepareFG();
				doSolving = true;
			}
		} else if ( mfg == null ) {
			prepareFG();
			doSolving = true;
		} else if ( numberDiverseSolutions > 1 && forceSolving ) {
			prepareFG();
			doSolving = true;
		}
		
		System.out.println(" ");
		System.out.println("finished preparation");

		if (doSolving) {
			fireNextProgressPhaseEvent( "Solving tracking with GUROBI...", 3 );
			fireProgressEvent();
    		solveFactorGraph();
			fireProgressEvent();
			if ( numberDiverseSolutions > 1 ) {
				imgSolutionList.clear();
				for ( Assignment< IndicatorNode > divSolution : pgSolutionList ) {
					imgSolutionList.add( SolutionVisulizer.drawSolutionSegmentImages( this, divSolution) );
				}
				if ( numberDiverseSolutions == 2 ) {
					imgSolutionList.clear();
					Assignment< IndicatorNode > divSolutionOne = pgSolutionList.get(0);
					Assignment< IndicatorNode > divSolutionTwo = pgSolutionList.get(1);
					imgSolutionList.add( SolutionVisulizer.drawSolutionSegmentImages( this, divSolutionOne, divSolutionTwo ) );
					imgSolutionList.add( SolutionVisulizer.drawSolutionSegmentImages( this, divSolutionTwo, divSolutionOne ) );
				}
			} else {
				imgSolution = SolutionVisulizer.drawSolutionSegmentImages( this, pgSolution );
			}
    		saveSolution();
			fireSolutionChangedEvent();
			fireProgressEvent();
		}
		
		fireProgressCompletedEvent();
	}

	/**
	 * (Re)runs the trackins problem in a thread of it's own.
	 * Additionally also takes care of the BDV.
	 *
	 * @param forceResolve
	 */
	public Thread runInThread( final boolean forceResolve ) {
		return this.runInThread( forceResolve, false );
	}

	/**
	 * (Re)runs the trackins problem in a thread of it's own.
	 * Additionally also takes care of the BDV.
	 */
	public Thread runInThread( final boolean forceResolve, final boolean forceRebuildPG ) {
//		final Tr2dTrackingModel self = this;
		final Runnable runnable = new Runnable() {

			@Override
			public void run() {
				Tr2dTrackingModel.this.run( forceResolve, forceRebuildPG );
				
				final int bdvTime = bdvHandlePanel.getViewerPanel().getState().getCurrentTimepoint();
				bdvRemoveAll();
				diverseSolutionBdvSources.clear();
				
				bdvAdd( getTr2dModel().getRawData(), "RAW" );
				bdvHandlePanel.getViewerPanel().setTimepoint( bdvTime );
				
				if ( numberDiverseSolutions > 1 ) {
					if ( imgSolutionList != null ) {
						int i = 0;
						for ( RandomAccessibleInterval< IntType > imgSol : imgSolutionList ) {
							if ( i == 0 )
								bdvAdd( imgSol, "solution 1", 0, 5, new ARGBType( 0x00FFFF ), true );
							else
								bdvAdd( imgSol, "solution " + ( i + 1 ), 0, 5, new ARGBType( 0xFFFF00 ), false );
							i++;
						}
						diverseSolutionBdvSources.addAll( bdvGetSources().subList( 1, bdvGetSources().size() ) );
						
						if ( trackingOverlay == null ) {
							trackingOverlay = new Tr2dTrackingOverlay( Tr2dTrackingModel.this );
							bdvAdd( trackingOverlay, "overlay tracking", true );
						}
						trackingOverlay.setActiveSolution(0);
					}
				}
				else {
					if ( imgSolution != null ) {
						bdvAdd( imgSolution, "solution", 0, 5, new ARGBType( 0x00FF00 ), true );
						diverseSolutionBdvSources.addAll( bdvGetSources().subList( 1, bdvGetSources().size() ) );
						
						if ( trackingOverlay == null ) {
							trackingOverlay = new Tr2dTrackingOverlay( Tr2dTrackingModel.this );
							bdvAdd( trackingOverlay, "overlay tracking", true );
						}
						trackingOverlay.setActiveSolution(0);
					}
				}
			}

		};
		final Thread t = new Thread( runnable );
		t.start();
		return t;
	}

	/**
	 *
	 */
	private void saveTrackingProblem() {
		try {
			tr2dTraProblem.saveToFile( dataFolder.getFile( FILENAME_PGRAPH ).getFile() );
		} catch ( final IOException e ) {
			e.printStackTrace();
		} catch ( final NullPointerException npe ) {
			Tr2dLog.log.error( "PGraph could not be stored to disk!" );
		}
	}

	/**
	 *
	 */
	private void saveFactorGraph() {
		Tr2dLog.log.debug( "Saveing of FGs not yet implemented!" );
	}

	/**
	 *
	 */
	private void saveSolution() {
		IJ.save(
				ImageJFunctions.wrap( imgSolution, "tracking solution" ).duplicate(),
				dataFolder.getFile( FILENAME_TRACKING ).getAbsolutePath() );
	}

	/**
	 * @return returns true if segmentations could be processed, false e.g. if
	 *         no segmentation was found.
	 */
	public boolean processSegmentationInputs( final boolean forceHypothesesRefetch ) {
		if ( forceHypothesesRefetch || labelingFrames.needProcessing() ) {
			if ( !labelingFrames.processFrames() ) {
				final String msg = "Segmentation Hypotheses could not be accessed!\nYou must create a segmentation prior to starting the tracking!";
				Tr2dLog.log.error( msg );
				JOptionPane.showMessageDialog( Tr2dContext.guiFrame, msg, "No segmentation found...", JOptionPane.ERROR_MESSAGE );
				return false;
			}
			labelingFrames.saveTo( hypothesesFolder, progressListeners );
		}
		return true;
	}

	/**
	 *
	 */
	public void buildTrackingProblem() {
		final TicToc tictoc = new TicToc();

		this.tr2dTraProblem =
				new Tr2dTrackingProblem(
						this,
						tr2dModel.getFlowModel(),
						appearanceCosts,
						moveCosts,
						divisionCosts,
						disappearanceCosts );

		fireNextProgressPhaseEvent( "Building tracking problem (PG)...", labelingFrames.getNumFrames() );
		for ( int frameId = 0; frameId < labelingFrames.getNumFrames(); frameId++ ) {
			Tr2dLog.log.info(
					String.format( "Working on frame %d of %d...", frameId + 1, labelingFrames.getNumFrames() ) );

			// =============================
			// build Tr2dSegmentationProblem
			// =============================
			tictoc.tic( "Constructing Tr2dSegmentationProblem..." );
			final List< LabelingSegment > segments =
					labelingFrames.getLabelingSegmentsForFrame( frameId );
			final ConflictGraph< LabelingSegment > conflictGraph =
					labelingFrames.getConflictGraph( frameId );
			final Tr2dSegmentationProblem segmentationProblem =
					new Tr2dSegmentationProblem( frameId, segments, segmentCosts, conflictGraph );
			tictoc.toc( "done!" );

			// =============================
			// add it to Tr2dTrackingProblem
			// =============================
			tictoc.tic( "Connect it to Tr2dTrackingProblem..." );
			tr2dTraProblem.addSegmentationProblem( segmentationProblem );
			tictoc.toc( "done!" );

			fireProgressEvent();
		}
		tr2dTraProblem.addDummyDisappearance();

		Tr2dLog.log.info( "Tracking graph was built sucessfully!" );
	}

	/**
	 *
	 */
	public void buildFactorGraph() {
		final TicToc tictoc = new TicToc();
		tictoc.tic( "Constructing FactorGraph for created Tr2dTrackingProblem..." );
		mfg = FactorGraphFactory.createFactorGraph( tr2dTraProblem, progressListeners );
		tictoc.toc( "done!" );

		if ( numberDiverseSolutions > 1 ) {
			tictoc.tic( "Duplicating FactorGraph for created Tr2dTrackingProblem..." );
			dmfg = FactorGraphFactory.extendFactorGraphForDiversity( mfg, numberDiverseSolutions, diverseSegmentCost, diverseAppearanceCost, diverseDisappearanceCost, diverseMovementCost, diverseDivisionCost, progressListeners );
			tictoc.toc( "done!" );
		}
	}

	/**
	 *
	 */
	private void solveFactorGraph() {
		if ( numberDiverseSolutions > 1 ) {
			final UnaryCostConstraintGraph fg = dmfg.getFg();
			final List< AssignmentMapper< Variable, IndicatorNode > > assMapperList = dmfg.getListOfAssignmentMaps();

			fgSolution = null;
//			pgSolution = null;
			pgSolutionList.clear();
			try {
				SolveGurobi.GRB_PRESOLVE = 0;
				solver = new SolveGurobi();
				fgSolution = solver.solve( fg, new DefaultLoggingGurobiCallback( Tr2dLog.gurobilog ) );
				for ( final AssignmentMapper< Variable, IndicatorNode > mapper : assMapperList ) {
					final Assignment< IndicatorNode > partSol = mapper.map(fgSolution);
					double partSolutionCost = 0; 
					for ( final IndicatorNode hypvar : mfg.getVarmap().valuesAs() ) {
						if ( partSol.getAssignment( hypvar ) == 1 ) {
							partSolutionCost = partSolutionCost + hypvar.getCost();
						}
					}
					System.out.println( "\n partial solution value: " + partSolutionCost );
					pgSolutionList.add( partSol );
				}
				pgSolution = pgSolutionList.get(0);
			} catch ( final GRBException e ) {
				e.printStackTrace();
			} catch ( final IllegalStateException ise ) {
				fgSolution = null;
				pgSolution = null;
				Tr2dLog.log.error( "Model is now infeasible and needs to be retracked!" );
				fireModelInfeasibleEvent();
			}
		}
		else {
			final UnaryCostConstraintGraph fg = mfg.getFg();
			final AssignmentMapper< Variable, IndicatorNode > assMapper = mfg.getAssmntMapper();
//			final Map< IndicatorNode, Variable > varMapper = mfg.getVarmap();

			fgSolution = null;
			pgSolutionList.clear();
			
			try {
				SolveGurobi.GRB_PRESOLVE = 0;
				solver = new SolveGurobi();
				fgSolution = solver.solve( fg, new DefaultLoggingGurobiCallback( Tr2dLog.gurobilog ) );
				pgSolution = assMapper.map( fgSolution );
				double partSolutionCost = 0; 
				for ( final IndicatorNode hypvar : mfg.getVarmap().valuesAs() ) {
					if ( pgSolution.getAssignment( hypvar ) == 1 ) {
						partSolutionCost = partSolutionCost + hypvar.getCost();
					}
				}
				System.out.println( "\n partial solution value: " + partSolutionCost );
				pgSolutionList.add( pgSolution );
			} catch ( final GRBException e ) {
				e.printStackTrace();
			} catch ( final IllegalStateException ise ) {
				fgSolution = null;
				pgSolution = null;
				Tr2dLog.log.error( "Model is now infeasible and needs to be retracked!" );
				fireModelInfeasibleEvent();
			}
		}
	}

	/**
	 * Opens the computed tracking solution image in ImageJ (if it was computed
	 * already). Does nothing otherwise.
	 */
	public void showSolutionInImageJ() {
		if ( imgSolution != null ) ImageJFunctions.show( imgSolution, "Solution" );
	}

	/**
	 * @return the imgSolution
	 */
	public RandomAccessibleInterval< IntType > getImgSolution() {
		return imgSolution;
	}

	/**
	 * @see com.indago.ui.bdv.BdvOwner#bdvSetHandlePanel(BdvHandlePanel)
	 */
	@Override
	public void bdvSetHandlePanel( final BdvHandlePanel bdvHandlePanel ) {
		this.bdvHandlePanel = bdvHandlePanel;
	}

	/**
	 * @see com.indago.ui.bdv.BdvOwner#bdvGetHandlePanel()
	 */
	@Override
	public BdvHandlePanel bdvGetHandlePanel() {
		return bdvHandlePanel;
	}

	/**
	 * @see com.indago.ui.bdv.BdvOwner#bdvGetSources()
	 */
	@Override
	public List< BdvSource > bdvGetSources() {
		return bdvSources;
	}

	/**
	 * @see com.indago.ui.bdv.BdvOwner#bdvGetSourceFor(net.imglib2.RandomAccessibleInterval)
	 */
	@Override
	public < T extends RealType< T > & NativeType< T > > BdvSource bdvGetSourceFor( final RandomAccessibleInterval< T > img ) {
		final int idx = imgs.indexOf( img );
		if ( idx == -1 ) return null;
		return bdvGetSources().get( idx );
	}

	/**
	 * @see com.indago.ui.bdv.BdvWithOverlaysOwner#bdvGetOverlays()
	 */
	@Override
	public List< BdvOverlay > bdvGetOverlays() {
		return overlays;
	}

	/**
	 * @see com.indago.ui.bdv.BdvWithOverlaysOwner#bdvGetOverlaySources()
	 */
	@Override
	public List< BdvSource > bdvGetOverlaySources() {
		return bdvOverlaySources;
	}

	/**
	 * @return
	 */
	public Tr2dTrackingProblem getTrackingProblem() {
		return this.tr2dTraProblem;
	}

	/**
	 * @return
	 */
	public Assignment< IndicatorNode > getSolution() {
		return getSolution(0);
	}

	/**
	 * @return
	 */
	public Assignment< IndicatorNode > getSolution(int i) {
		if ( i < this.pgSolutionList.size() && i >= 0 )
			return this.pgSolutionList.get(i);
		else
			return this.pgSolution;
	}

	/**
	 * @return the tr2dModel
	 */
	public Tr2dModel getTr2dModel() {
		return tr2dModel;
	}

	/**
	 * @return the labelingFrames
	 */
	public LabelingTimeLapse getLabelingFrames() {
		return labelingFrames;
	}

	/**
	 * @return the costFactories
	 */
	public List< CostFactory< ? > > getCostFactories() {
		return costFactories;
	}

	/**
	 * @return the fgSolution
	 */
	public Assignment< Variable > getFgSolution() {
		return fgSolution;
	}

	/**
	 * Recomputes all costs for the PG.
	 * TODO: use this function also in the first place when building the PG (otherwise inconsistencies might occur!).
	 */
	public void updateCosts() {
		// Update all assignment costs in PG...
		for ( final Tr2dSegmentationProblem tp : tr2dTraProblem.getTimepoints() ) {
			for ( final SegmentNode segNode : tp.getSegments() ) {
				final LabelingSegment labelingSegment = tp.getLabelingSegment( segNode );
				final SegmentNode segVar = tp.getSegmentVar( labelingSegment );

				// SEGMENT COST UPDATE
				tp.getSegmentVar( labelingSegment ).setCost( segmentCosts.getCost( labelingSegment ) );

				// APPEARANCE COST UPDATE
				for ( final AppearanceHypothesis inApp : tp.getSegmentVar( labelingSegment ).getInAssignments().getAppearances() ) {
					inApp.setCost( appearanceCosts.getCost( labelingSegment ) );
				}

				// DISAPPEARANCE COST UPDATE
				final AssignmentNodes outass = tp.getSegmentVar( labelingSegment ).getOutAssignments();
				for ( final DisappearanceHypothesis outDisapp : outass.getDisappearances() ) {
					outDisapp.setCost( disappearanceCosts.getCost( labelingSegment ) );
				}

				// MOVEMENT COST UPDATE
				for ( final MovementHypothesis outMove : outass.getMoves() ) {
					// retrieve flow vector at desired location
					final int t = tp.getTime();
					final int x = ( int ) segVar.getSegment().getCenterOfMass().getFloatPosition( 0 );
					final int y = ( int ) segVar.getSegment().getCenterOfMass().getFloatPosition( 1 );
					final ValuePair< Double, Double > flow_vec = tr2dModel.getFlowModel().getFlowVector( t, x, y );

					final double cost_flow = moveCosts.getCost(
							new ValuePair<>( new ValuePair< LabelingSegment, LabelingSegment >( labelingSegment, outMove
									.getDest().getSegment() ), flow_vec ) );
					//Tr2dLog.log.trace( "Movement cost: " + cost_flow + "; " + moveCosts.getParameters().get( 0 ) );
					outMove.setCost( cost_flow );
				}

				// DIVISION COST UPDATE
				for ( final DivisionHypothesis outDiv : outass.getDivisions() ) {
					final ValuePair< LabelingSegment, Pair< LabelingSegment, LabelingSegment > > param =
							new ValuePair<>( labelingSegment,
									new ValuePair<>( outDiv.getDest1().getSegment(), outDiv.getDest2().getSegment() ) );
					final double cost = divisionCosts.getCost( param );
					outDiv.setCost( cost );
				}
			}
		}
	}

	public void addSolutionChangedListener( final SolutionChangedListener scl ) {
		solChangedListeners.add( scl );
	}

	public void fireSolutionChangedEvent() {
		for ( final SolutionChangedListener scl : solChangedListeners ) {
			scl.solutionChanged( pgSolution );
		}
	}

	public void addModelInfeasibleListener( final ModelInfeasibleListener mil ) {
		modelInfeasibleListeners.add( mil );
	}

	private void fireModelInfeasibleEvent() {
		for ( final ModelInfeasibleListener mil : modelInfeasibleListeners ) {
			mil.modelIsInfeasible();
		}
	}

	/**
	 * @return the mfg
	 */
	public MappedFactorGraph getMappedFactorGraph() {
		return mfg;
	}

	/**
	 * @return the dmfg
	 */
	public MappedDiverseFactorGraph getMappedDiverseFactorGraph() {
		return dmfg;
	}

	/**
	 * @param progressListener
	 */
	public void addProgressListener( final ProgressListener progressListener ) {
		this.progressListeners.add( progressListener );
	}

	/**
	 * @param maxProgress
	 */
	public void setTotalProgressSteps( final int maxProgress ) {
		for ( final ProgressListener progressListener : this.progressListeners ) {
			progressListener.setTotalProgressSteps( maxProgress );
		}
	}

	/**
	 *
	 */
	public void fireProgressEvent() {
		for ( final ProgressListener progressListener : this.progressListeners ) {
			progressListener.hasProgressed();
		}
	}

	/**
	 * @param newMessage
	 */
	public void fireProgressEvent( final String newMessage ) {
		for ( final ProgressListener progressListener : this.progressListeners ) {
			progressListener.hasProgressed( newMessage );
		}
	}

	/**
	 * @param newMessage
	 * @param maxProgress
	 */
	public void fireNextProgressPhaseEvent( final String newMessage, final int maxProgress ) {
		for ( final ProgressListener progressListener : this.progressListeners ) {
			progressListener.resetProgress( newMessage, maxProgress );
		}
	}

	public void fireProgressCompletedEvent() {
		for ( final ProgressListener progressListener : this.progressListeners ) {
			progressListener.hasCompleted();
		}
	}

	/**
	 * @param progress
	 */
	public void removeProgressListener( final DialogProgress progress ) {
		this.progressListeners.remove( progress );
	}

	/**
	 * @return the solver
	 */
	public SolveGurobi getSolver() {
		return solver;
	}

	/**
	 * @return the maxMovementToAddRadius
	 */
	public double getMaxMovementSearchRadius() {
		return maxMovementSearchRadius;
	}

	/**
	 * @param maxMovementToAddRadius
	 *            the maxMovementToAddRadius to set
	 */
	public void setMaxMovementSearchRadius( final double maxMovementToAddRadius ) {
		this.maxMovementSearchRadius = maxMovementToAddRadius;
	}

	/**
	 * @return the maxDivisionToAddRadius
	 */
	public double getMaxDivisionSearchRadius() {
		return maxDivisionSearchRadius;
	}

	/**
	 * @param maxDivisionToAddRadius
	 *            the maxDivisionToAddRadius to set
	 */
	public void setMaxDivisionSearchRadius( final double maxDivisionToAddRadius ) {
		this.maxDivisionSearchRadius = maxDivisionToAddRadius;
	}

	/**
	 * @return the maxMovementsToAddPerHypothesis
	 */
	public int getMaxMovementsToAddPerHypothesis() {
		return maxMovementsToAddPerHypothesis;
	}

	/**
	 * @param maxMovementsToAddPerHypothesis
	 *            the maxMovementsToAddPerHypothesis to set
	 */
	public void setMaxMovementsToAddPerHypothesis( final int maxMovementsToAddPerHypothesis ) {
		this.maxMovementsToAddPerHypothesis = maxMovementsToAddPerHypothesis;
	}

	/**
	 * @return the maxDivisionsToAddPerHypothesis
	 */
	public int getMaxDivisionsToAddPerHypothesis() {
		return maxDivisionsToAddPerHypothesis;
	}

	/**
	 * @param maxDivisionsToAddPerHypothesis
	 *            the maxDivisionsToAddPerHypothesis to set
	 */
	public void setMaxDivisionsToAddPerHypothesis( final int maxDivisionsToAddPerHypothesis ) {
		this.maxDivisionsToAddPerHypothesis = maxDivisionsToAddPerHypothesis;
	}

	/**
	 * @return the numberDiverseSolutions
	 */
	public int getNumberDiverseSolutions() {
		return numberDiverseSolutions;
	}

	/**
	 * @param numberDiverseSolutions
	 *            the number of diverse solutions to compute
	 */
	public void setNumberDiverseSolutions( final int numberDiverseSolutions ) {
		this.numberDiverseSolutions = numberDiverseSolutions;
	}

	/**
	 * @return the diverseSegmentCost
	 */
	public double getDiverseSegmentCost() {
		return diverseSegmentCost;
	}

	/**
	 * @param diverseSegmentCost the diverseSegmentCost to set
	 */
	public void setDiverseSegmentCost(double diverseSegmentCost) {
		this.diverseSegmentCost = diverseSegmentCost;
	}

	/**
	 * @return the diverseAppearanceCost
	 */
	public double getDiverseAppearanceCost() {
		return diverseAppearanceCost;
	}

	/**
	 * @param diverseAppearanceCost the diverseAppearanceCost to set
	 */
	public void setDiverseAppearanceCost(double diverseAppearanceCost) {
		this.diverseAppearanceCost = diverseAppearanceCost;
	}

	/**
	 * @return the diverseDisappearanceCost
	 */
	public double getDiverseDisappearanceCost() {
		return diverseDisappearanceCost;
	}

	/**
	 * @param diverseDisappearanceCost the diverseDisappearanceCost to set
	 */
	public void setDiverseDisappearanceCost(double diverseDisappearanceCost) {
		this.diverseDisappearanceCost = diverseDisappearanceCost;
	}

	/**
	 * @return the diverseMovementCost
	 */
	public double getDiverseMovementCost() {
		return diverseMovementCost;
	}

	/**
	 * @param diverseMovementCost the diverseMovementCost to set
	 */
	public void setDiverseMovementCost(double diverseMovementCost) {
		this.diverseMovementCost = diverseMovementCost;
	}

	/**
	 * @return the diverseDivisionCost
	 */
	public double getDiverseDivisionCost() {
		return diverseDivisionCost;
	}

	/**
	 * @param diverseDivisionCost the diverseDivisionCost to set
	 */
	public void setDiverseDivisionCost(double diverseDivisionCost) {
		this.diverseDivisionCost = diverseDivisionCost;
	}

	public List<BdvSource> getDiverseSolutionBdvSources() {
		return diverseSolutionBdvSources;
	}

	public Tr2dTrackingOverlay getTr2dTrackingOverlay() {
		return trackingOverlay;
	}

	/**
	 *
	 */
	public void saveStateToFile() {
		try {
			final FileWriter writer = new FileWriter( new File( dataFolder.getFolder(), FILENAME_STATE ) );
			writer.append( "" + this.maxMovementSearchRadius );
			writer.append( ", " );
			writer.append( "" + this.maxMovementsToAddPerHypothesis );
			writer.append( ", " );
			writer.append( "" + this.maxDivisionSearchRadius );
			writer.append( ", " );
			writer.append( "" + this.maxDivisionsToAddPerHypothesis );
			writer.append( ", " );
			writer.flush();
			writer.close();
		} catch ( final IOException e ) {
			e.printStackTrace();
		}
	}

	private void loadStateFromProjectFolder() {
		final CsvParser parser = new CsvParser( new CsvParserSettings() );

		final File guiState = dataFolder.addFile( FILENAME_STATE ).getFile();
		try {
			final List< String[] > rows = parser.parseAll( new FileReader( guiState ) );
			final String[] strings = rows.get( 0 );
			try {
				this.maxMovementSearchRadius = Double.parseDouble( strings[ 0 ] );
				this.maxMovementsToAddPerHypothesis = Integer.parseInt( strings[ 1 ] );
				this.maxDivisionSearchRadius = Double.parseDouble( strings[ 2 ] );
				this.maxDivisionsToAddPerHypothesis = Integer.parseInt( strings[ 3 ] );
			} catch ( final NumberFormatException e ) {
				this.maxMovementSearchRadius = 25;
				this.maxMovementsToAddPerHypothesis = 5;
				this.maxDivisionSearchRadius = 25;
				this.maxDivisionsToAddPerHypothesis = 5;
			}
		} catch ( final FileNotFoundException e ) {}
		fireStateChangedEvent();
	}

	public void addStateChangedListener( final ChangeListener listener ) {
		if ( !stateChangedListeners.contains( listener ) )
			stateChangedListeners.add( listener );
	}

	public void removeStateChangedListener( final ChangeListener listener ) {
		if ( stateChangedListeners.contains( listener ) )
			stateChangedListeners.remove( listener );
	}

	public void fireStateChangedEvent() {
		stateChangedListeners.forEach( l -> l.stateChanged( null ) );
	}
}
