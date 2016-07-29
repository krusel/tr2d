/**
 *
 */
package com.indago.tr2d.ui.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import com.indago.io.FloatTypeImgLoader;
import com.indago.io.projectfolder.ProjectFile;
import com.indago.io.projectfolder.ProjectFolder;
import com.indago.tr2d.io.projectfolder.Tr2dProjectFolder;
import com.indago.tr2d.ui.view.bdv.BdvOwner;

import bdv.util.BdvHandlePanel;
import bdv.util.BdvSource;
import io.scif.img.ImgIOException;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import weka.gui.ExtensionFileFilter;

/**
 * @author jug
 */
public class Tr2dFlowModel implements BdvOwner {

	private final Tr2dModel model;

	private final ProjectFolder projectFolder;

	private BdvHandlePanel bdvHandlePanel;
	private final List< BdvSource > bdvSources = new ArrayList<>();
	private final List< RandomAccessibleInterval< FloatType > > imgs = new ArrayList<>();

	/**
	 * @param model
	 */
	public Tr2dFlowModel( final Tr2dModel model ) {
		this.model = model;
		projectFolder = model.getProjectFolder().getFolder( Tr2dProjectFolder.FLOW_FOLDER );

		this.projectFolder.loadFiles();
		final Vector< ProjectFile > files =
				new Vector< ProjectFile >( projectFolder.getFiles( new ExtensionFileFilter( "tif", "TIFF Image Stack" ) ) );
		if ( files.size() != 1 ) {
			System.err.println( "ERROR: Flow subfolder does not contain any or more than one image files." );
		} else {
			try {
				imgs.add( FloatTypeImgLoader.loadTiff( files.get( 0 ).getFile() ) );
			} catch ( final ImgIOException e ) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * @return
	 */
	public ProjectFolder getProjectFolder() {
		return projectFolder;
	}

	/**
	 * @return
	 */
	public Tr2dModel getModel() {
		return model;
	}

	/**
	 * @return
	 */
	public RandomAccessibleInterval< FloatType > getFlowImage() {
		return imgs.get( 0 );
	}

	/**
	 * @return
	 */
	public RandomAccessibleInterval< FloatType > getFlowImage( final int t ) {
		return Views.hyperSlice( imgs.get( 0 ), 3, t );
	}

	/**
	 * @return
	 */
	public ValuePair< Double, Double > getFlowVector( final int t, final int x, final int y ) {
		final double dx = 0;
		final double dy = 0;
		final double[] polar = new double[ 2 ];

		final RandomAccess< FloatType > ra = imgs.get( 0 ).randomAccess();

		final int[] pos_for_x_direction = new int[] { x, y, 0, t };
		ra.setPosition( pos_for_x_direction );
		polar[ 0 ] = ra.get().get();

		final int[] pos_for_y_direction = new int[] { x, y, 1, t };
		ra.setPosition( pos_for_y_direction );
		polar[ 1 ] = ra.get().get();

		// GET dx, dy FROM POLAR (attention to NaN)

		return new ValuePair<>( dx, dy );
	}

	/**
	 * @see com.indago.tr2d.ui.view.bdv.BdvOwner#setBdvHandlePanel()
	 */
	@Override
	public void bdvSetHandlePanel( final BdvHandlePanel bdvHandlePanel ) {
		this.bdvHandlePanel = bdvHandlePanel;
	}

	/**
	 * @see com.indago.tr2d.ui.view.bdv.BdvOwner#bdvGetHandlePanel()
	 */
	@Override
	public BdvHandlePanel bdvGetHandlePanel() {
		return bdvHandlePanel;
	}

	/**
	 * @see com.indago.tr2d.ui.view.bdv.BdvOwner#bdvGetSources()
	 */
	@Override
	public List< BdvSource > bdvGetSources() {
		return bdvSources;
	}

	/**
	 * @see com.indago.tr2d.ui.view.bdv.BdvOwner#bdvGetSourceFor(net.imglib2.RandomAccessibleInterval)
	 */
	@Override
	public < T extends RealType< T > & NativeType< T > > BdvSource bdvGetSourceFor( final RandomAccessibleInterval< T > img ) {
		final int idx = imgs.indexOf( img );
		if ( idx == -1 ) return null;
		return bdvGetSources().get( idx );
	}
}