/**
 *
 */
package com.indago.tr2d.ui.model;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
import com.indago.tr2d.ui.util.SolutionVisualizer;
import com.indago.tr2d.ui.view.bdv.overlays.Tr2dFlowOverlay;
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
	private final String FILENAME_COST_PARAMS = "cost_params.tr2dcosts";
	private final ProjectFolder dataFolder;

	private final String FOLDER_LABELING_FRAMES = "labeling_frames";
	private ProjectFolder hypothesesFolder = null;

	private final String FILENAME_PGRAPH = "tracking.pgraph";
	private final String FILENAME_TRACKING = "tracking.tif";

	private final Tr2dModel tr2dModel;
	private final Tr2dSegmentationEditorModel tr2dSegEditModel;

	private int maxMovementSearchRadius = 50;
	private int maxDivisionSearchRadius = 50;
	private int maxMovementsToAddPerHypothesis = 4;
	private int maxDivisionsToAddPerHypothesis = 8;
	private int maxPixelComponentSize = 32; // gets set to more sensible value in constructor
	private int minPixelComponentSize = 16;

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
		this.maxPixelComponentSize = this.tr2dModel.getImgPlus().getWidth() * this.tr2dModel.getImgPlus().getHeight() - 1;

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

		this.tr2dSegEditModel = model.getSegmentationEditorModel();

		final File fImgSol = dataFolder.addFile( FILENAME_TRACKING ).getFile();
		if ( fImgSol.canRead() ) {
			imgSolution = IntTypeImgLoader.loadTiffEnsureType( fImgSol );
		}
		imgs = new ArrayList< >();
		imgs.add( imgSolution );

		// Loading hypotheses labeling frames if exist in project folder
		this.labelingFrames = new LabelingTimeLapse( tr2dSegEditModel, this.getMinPixelComponentSize(), this.getMaxPixelComponentSize() );
		try {
			hypothesesFolder = dataFolder.addFolder( FOLDER_LABELING_FRAMES );
			hypothesesFolder.loadFiles();
			labelingFrames.loadFromProjectFolder( hypothesesFolder );
		} catch ( final IOException ioe ) {
			ioe.printStackTrace();
		}

		loadStateFromProjectFolder();
		loadCostParametersFromProjectFolder();
	}

	/**
	 * (Re-)fetches all hypotheses and marks this tracking model as 'reset'.
	 */
	public void fetch() {
		for ( final ProgressListener progressListener : progressListeners ) {
			progressListener.resetProgress( "Purging currently fetched segment hypotheses... (1/3)", 3 );
		}

		// clear BDV content
		bdvRemoveAll();
		bdvRemoveAllOverlays();
		diverseSolutionBdvSources.clear();

		for ( final ProgressListener progressListener : progressListeners ) {
			progressListener.hasProgressed( "Purging currently fetched segment hypotheses... (2/3)" );
		}

		// purge segmentation data
		dataFolder.getFile( FILENAME_TRACKING ).getFile().delete();
		try {
			dataFolder.getFolder( FOLDER_LABELING_FRAMES ).deleteContent();
		} catch ( final IOException e ) {
			if ( dataFolder.getFolder( FOLDER_LABELING_FRAMES ).exists() ) {
				Tr2dLog.log.error( "Labeling frames exist but cannot be deleted." );
			}
		}

		// recollect segmentation data
		processSegmentationInputs( true );

		// purge problem graph
		tr2dTraProblem = null;

		for ( final ProgressListener progressListener : progressListeners ) {
			progressListener.hasCompleted();
		}
	}

	/**
	 * Prepares the tracking model (Step1: builds pg and stores intermediate
	 * data in
	 * project folder).
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

		if (doSolving) {
			fireNextProgressPhaseEvent( "Solving tracking with GUROBI...", 3 );
			fireProgressEvent();
    		solveFactorGraph();
			fireProgressEvent();
			if ( numberDiverseSolutions > 1 ) {
				imgSolutionList.clear();
				for ( Assignment< IndicatorNode > divSolution : pgSolutionList ) {
					imgSolutionList.add( SolutionVisualizer.drawSolutionSegmentImages( this, divSolution) );
				}
				if ( numberDiverseSolutions == 2 ) {
					imgSolutionList.clear();
					Assignment< IndicatorNode > divSolutionOne = pgSolutionList.get(0);
					Assignment< IndicatorNode > divSolutionTwo = pgSolutionList.get(1);
					imgSolutionList.add( SolutionVisualizer.drawSolutionSegmentImages( this, divSolutionOne, divSolutionTwo ) );
					imgSolutionList.add( SolutionVisualizer.drawSolutionSegmentImages( this, divSolutionTwo, divSolutionOne ) );
				}
			} else {
				imgSolution = SolutionVisualizer.drawSolutionSegmentImages( this, pgSolution );
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
	 *            should resolve be forced?
	 * @return the created thread the run is performed in
	 */
	public Thread runInThread( final boolean forceResolve ) {
		return this.runInThread( forceResolve, false );
	}

	/**
	 * (Re)runs the trackins problem in a thread of it's own.
	 * Additionally also takes care of the BDV.
	 *
	 * @param forceResolve
	 *            should resolve be forced?
	 * @param forceRebuildPG
	 *            should rebuild of the problem graph be forced?
	 * @return the created thread the run is performed in
	 */
	public Thread runInThread( final boolean forceResolve, final boolean forceRebuildPG ) {
		final Runnable runnable = new Runnable() {

			@Override
			public void run() {
				bdvRemoveAllOverlays();
				bdvRemoveAll();
				diverseSolutionBdvSources.clear();

				Tr2dTrackingModel.this.run( forceResolve, forceRebuildPG );

				final int bdvTime = bdvHandlePanel.getViewerPanel().getState().getCurrentTimepoint();
				populateBdv();
				bdvHandlePanel.getViewerPanel().setTimepoint( bdvTime );
			}

		};
		final Thread t = new Thread( runnable );
		t.start();
		return t;
	}

	public void populateBdv() {
		bdvRemoveAll();
		bdvRemoveAllOverlays();
		diverseSolutionBdvSources.clear();
		
		bdvAdd( getTr2dModel().getRawData(), "RAW", false );
//		if ( getImgSolution() != null ) {
//			bdvAdd( getImgSolution(), "solution", 0, 5, new ARGBType( 0x00FF00 ), true );
//		}
//		bdvAdd( new Tr2dTrackingOverlay( this ), "overlay_tracking" );
		
		if ( numberDiverseSolutions > 1 ) {
			if ( getImgSolutionList() != null ) {
				int i = 0;
				for ( RandomAccessibleInterval< IntType > imgSol : getImgSolutionList() ) {
					if ( i == 0 )
						bdvAdd( imgSol, "solution 1", 0, 5, new ARGBType( 0xFFFF00 ), true );
					else
						bdvAdd( imgSol, "solution " + ( i + 1 ), 0, 5, new ARGBType( 0x00FFFF ), false );
					i++;
				}
			}
		}
		else {
			if ( getImgSolution() != null ) {
				bdvAdd( getImgSolution(), "solution", 0, 5, new ARGBType( 0x00FF00 ), true );
			}
		}
		diverseSolutionBdvSources.addAll( bdvGetSources().subList( 1, bdvGetSources().size() ) );
		
		trackingOverlay = new Tr2dTrackingOverlay( this );

		bdvAdd( trackingOverlay, "overlay tracking" );
		trackingOverlay.setActiveSolution(0);
		
		bdvAdd( new Tr2dFlowOverlay( getTr2dModel().getFlowModel() ), "overlay_flow", false );
	}

	private void saveTrackingProblem() {
		try {
			tr2dTraProblem.saveToFile( dataFolder.getFile( FILENAME_PGRAPH ).getFile() );
		} catch ( final IOException e ) {
			e.printStackTrace();
		} catch ( final NullPointerException npe ) {
			Tr2dLog.log.error( "PGraph could not be stored to disk!" );
		}
	}

	private void saveFactorGraph() {
		Tr2dLog.log.debug( "Saveing of FGs not yet implemented!" );
	}

	private void saveSolution() {
		IJ.save(
				ImageJFunctions.wrap( imgSolution, "tracking solution" ).duplicate(),
				dataFolder.getFile( FILENAME_TRACKING ).getAbsolutePath() );
	}

	/**
	 * @param forceHypothesesRefetch
	 *            should hypothesis refetch be forced?
	 * @return returns true if segmentations could be processed, false e.g. if
	 *         no segmentation was found.
	 */
	public boolean processSegmentationInputs( final boolean forceHypothesesRefetch ) {

		if ( forceHypothesesRefetch || labelingFrames.needProcessing() ) {

			labelingFrames.setMinSegmentSize( getMinPixelComponentSize() );
			labelingFrames.setMaxSegmentSize( getMaxPixelComponentSize() );

			if ( !labelingFrames.processFrames( progressListeners ) ) {
				final String msg = "Segmentation Hypotheses could not be accessed!\nYou must create a segmentation prior to starting the tracking!";
				Tr2dLog.log.error( msg );
				JOptionPane.showMessageDialog( Tr2dContext.guiFrame, msg, "No segmentation found...", JOptionPane.ERROR_MESSAGE );
				return false;
			}
			labelingFrames.saveTo( hypothesesFolder, progressListeners );
		}
		return true;
	}

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

	private void solveFactorGraph() {
		if ( numberDiverseSolutions > 1 ) {
			final UnaryCostConstraintGraph fg = dmfg.getFg();
			final List< AssignmentMapper< Variable, IndicatorNode > > assMapperList = dmfg.getListOfAssignmentMaps();

			fgSolution = null;
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
				pgSolutionList.clear();
				Tr2dLog.log.error( "Model is now infeasible and needs to be retracked!" );
				fireModelInfeasibleEvent();
			}
		}
		else {
			final UnaryCostConstraintGraph fg = mfg.getFg();
			final AssignmentMapper< Variable, IndicatorNode > assMapper = mfg.getAssmntMapper();

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
				pgSolutionList.clear();
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

	public List< RandomAccessibleInterval< IntType > > getImgSolutionList() {
		return imgSolutionList;
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

	public Tr2dTrackingProblem getTrackingProblem() {
		return this.tr2dTraProblem;
	}

	public Assignment< IndicatorNode > getSolution() {
		return getSolution(0);
	}

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

	public MappedDiverseFactorGraph getMappedDiverseFactorGraph() {
		return dmfg;
	}

	public void addProgressListener( final ProgressListener progressListener ) {
		this.progressListeners.add( progressListener );
	}

	public void setTotalProgressSteps( final int maxProgress ) {
		for ( final ProgressListener progressListener : this.progressListeners ) {
			progressListener.setTotalProgressSteps( maxProgress );
		}
	}

	public void fireProgressEvent() {
		for ( final ProgressListener progressListener : this.progressListeners ) {
			progressListener.hasProgressed();
		}
	}

	public void fireProgressEvent( final String newMessage ) {
		for ( final ProgressListener progressListener : this.progressListeners ) {
			progressListener.hasProgressed( newMessage );
		}
	}

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
	public int getMaxMovementSearchRadius() {
		return maxMovementSearchRadius;
	}

	/**
	 * @param maxMovementToAddRadius
	 *            the maxMovementToAddRadius to set
	 */
	public void setMaxMovementSearchRadius( final int maxMovementToAddRadius ) {
		this.maxMovementSearchRadius = maxMovementToAddRadius;
	}

	/**
	 * @return the maxDivisionToAddRadius
	 */
	public int getMaxDivisionSearchRadius() {
		return maxDivisionSearchRadius;
	}

	/**
	 * @param maxDivisionToAddRadius
	 *            the maxDivisionToAddRadius to set
	 */
	public void setMaxDivisionSearchRadius( final int maxDivisionToAddRadius ) {
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
	 * @return the maximum size (in pixels) a component can be in order
	 *         to count as a valid segmentation hypothesis.
	 */
	public int getMaxPixelComponentSize() {
		return maxPixelComponentSize;
	}

	/**
	 * @param maxPixelComponentSize
	 *            the maximum size (in pixels) a component can be in order
	 *            to count as a valid segmentation hypothesis.
	 */
	public void setMaxPixelComponentSize( final int maxPixelComponentSize ) {
		this.maxPixelComponentSize = maxPixelComponentSize;
	}

	/**
	 * @return the minimum size (in pixels) a component needs to be in order
	 *         to count as a valid segmentation hypothesis.
	 */
	public int getMinPixelComponentSize() {
		return minPixelComponentSize;
	}

	/**
	 * @param minPixelComponentSize
	 *            the minimum size (in pixels) a component needs to be in order
	 *            to count as a valid segmentation hypothesis.
	 */
	public void setMinPixelComponentSize( final int minPixelComponentSize ) {
		this.minPixelComponentSize = minPixelComponentSize;
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

	public void saveStateToFile() {
		try {
			final FileWriter writer = new FileWriter( new File( dataFolder.getFolder(), FILENAME_STATE ) );
			writer.append( "" + this.maxPixelComponentSize );
			writer.append( ", " );
			writer.append( "" + this.minPixelComponentSize );
			writer.append( ", " );
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
				this.maxPixelComponentSize = Integer.parseInt( strings[ 0 ] );
				this.minPixelComponentSize = Integer.parseInt( strings[ 1 ] );
				this.maxMovementSearchRadius = Integer.parseInt( strings[ 2 ] );
				this.maxMovementsToAddPerHypothesis = Integer.parseInt( strings[ 3 ] );
				this.maxDivisionSearchRadius = Integer.parseInt( strings[ 4 ] );
				this.maxDivisionsToAddPerHypothesis = Integer.parseInt( strings[ 5 ] );
			} catch ( final NumberFormatException e ) {
				this.maxPixelComponentSize = this.tr2dModel.getImgPlus().getWidth() * this.tr2dModel.getImgPlus().getHeight() - 1;
				this.minPixelComponentSize = 16;
				this.maxMovementSearchRadius = 50;
				this.maxMovementsToAddPerHypothesis = 4;
				this.maxDivisionSearchRadius = 50;
				this.maxDivisionsToAddPerHypothesis = 8;
			}
		} catch ( final FileNotFoundException e ) {}
		fireStateChangedEvent();
	}

	private void loadCostParametersFromProjectFolder() {
		final File costParamsFile = dataFolder.addFile( FILENAME_COST_PARAMS ).getFile();
		try {
			importCostParametrization( costParamsFile );
			fireStateChangedEvent();
		} catch ( final IOException e ) {
			Tr2dLog.log.warn( "No cost parameter file found in project folder. Falling back to default values." );
		}
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

	/**
	 * Saves all cost parameters to a file called
	 * <code>FILENAME_COST_PARAMS</code> using
	 * <code>exportCostParametrization(...)</code>.
	 */
	public void saveCostParametersToProjectFolder() {
		try {
			exportCostParametrization( dataFolder.getFile( FILENAME_COST_PARAMS ).getFile() );
		} catch ( final IOException e ) {
			Tr2dLog.log.error( "Cannot save cost parameters to file '" + dataFolder.getFile( FILENAME_COST_PARAMS ).getAbsolutePath() + "'." );
		}
	}

	public void importCostParametrization( final File costsFile ) throws IOException {
		final BufferedReader costReader = new BufferedReader( new FileReader( costsFile ) );

		String line = "";
		for ( final CostFactory< ? > cf : getCostFactories() ) {
			final double[] params = cf.getParameters().getAsArray();
			for ( int j = 0; j < params.length; j++ ) {
				do {
					line = costReader.readLine();
				}
				while ( line.trim().startsWith( "#" ) || line.trim().isEmpty() );

				params[ j ] = Double.parseDouble( line );
			}
			cf.getParameters().setFromArray( params );
		}
		costReader.close();
	}

	public void exportCostParametrization( final File costsFile ) throws IOException {
		final SimpleDateFormat sdfDate = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" );
		final Date now = new Date();
		final String strNow = sdfDate.format( now );

		final BufferedWriter costWriter = new BufferedWriter( new FileWriter( costsFile ) );
		costWriter.write( "# Tr2d cost parameters export from " + strNow + "\n" );

		for ( final CostFactory< ? > cf : getCostFactories() ) {
			costWriter.write( String.format( "# PARAMS FOR: %s\n", cf.getName() ) );
			final double[] params = cf.getParameters().getAsArray();
			for ( int j = 0; j < params.length; j++ ) {
				costWriter.write( String.format( "# >> %s\n", cf.getParameters().getName( j ) ) );
				costWriter.write( String.format( "%f\n", params[ j ] ) );
			}
		}
		costWriter.flush();
		costWriter.close();
	}

}
