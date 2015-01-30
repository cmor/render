package org.janelia.alignment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import mpicbg.models.AffineModel2D;
import mpicbg.models.HomographyModel2D;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.Spring;
import mpicbg.models.SpringMesh;
import mpicbg.models.Tile;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.Vertex;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;
import mpicbg.trakem2.transform.MovingLeastSquaresTransform2;
import mpicbg.trakem2.transform.RestrictedMovingLeastSquaresTransform2;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

public class OptimizeLayersElastic {
	@Parameters
	static private class Params
	{
		@Parameter( names = "--help", description = "Display this note", help = true )
        private final boolean help = false;

        @Parameter( names = "--corrFiles", description = "Correspondence json files  (space separated) or a single file containing a line-separated list of json files", variableArity = true, required = true )
        public List<String> corrFiles = new ArrayList<String>();
        
        @Parameter( names = "--tilespecFiles", description = "Tilespec json files  (space separated) or a single file containing a line-separated list of json files", variableArity = true, required = true )
        public List<String> tileSpecFiles = new ArrayList<String>();
        
        @Parameter( names = "--fixedLayers", description = "Fixed layer numbers (space separated)", variableArity = true, required = true )
        public List<Integer> fixedLayers = new ArrayList<Integer>();
        
        @Parameter( names = "--imageWidth", description = "The width of the entire image (all layers), for consistent mesh computation", required = true )
        private int imageWidth;

        @Parameter( names = "--imageHeight", description = "The height of the entire image (all layers), for consistent mesh computation", required = true )
        private int imageHeight;

        @Parameter( names = "--targetDir", description = "Directory to output the new tilespec files", required = true )
        public String targetDir;

        @Parameter( names = "--maxLayersDistance", description = "The number of neighboring layers to match", required = false )
        private int maxLayersDistance;               

        @Parameter( names = "--modelIndex", description = "Model Index: 0=Translation, 1=Rigid, 2=Similarity, 3=Affine, 4=Homography", required = false )
        private int modelIndex = 1;
        
        @Parameter( names = "--layerScale", description = "Layer scale", required = false )
        private float layerScale = 0.1f;
        
        @Parameter( names = "--resolutionSpringMesh", description = "resolutionSpringMesh", required = false )
        private int resolutionSpringMesh = 32;
        
        //@Parameter( names = "--springLengthSpringMesh", description = "springLengthSpringMesh", required = false )
        //private float springLengthSpringMesh = 100f;
		
        @Parameter( names = "--stiffnessSpringMesh", description = "stiffnessSpringMesh", required = false )
        private float stiffnessSpringMesh = 0.1f;
		
        @Parameter( names = "--dampSpringMesh", description = "dampSpringMesh", required = false )
        private float dampSpringMesh = 0.9f;
		
        @Parameter( names = "--maxStretchSpringMesh", description = "maxStretchSpringMesh", required = false )
        private float maxStretchSpringMesh = 2000.0f;
        
        @Parameter( names = "--maxEpsilon", description = "maxEpsilon", required = false )
        private float maxEpsilon = 200.0f;
        
        @Parameter( names = "--maxIterationsSpringMesh", description = "maxIterationsSpringMesh", required = false )
        private int maxIterationsSpringMesh = 1000;
        
        @Parameter( names = "--maxPlateauwidthSpringMesh", description = "maxPlateauwidthSpringMesh", required = false )
        private int maxPlateauwidthSpringMesh = 200;
        
        //@Parameter( names = "--resolutionOutput", description = "resolutionOutput", required = false )
        //private int resolutionOutput = 128;
        
        @Parameter( names = "--useLegacyOptimizer", description = "Use legacy optimizer", required = false )
        private boolean useLegacyOptimizer = false;

        @Parameter( names = "--threads", description = "Number of threads to be used", required = false )
        public int numThreads = Runtime.getRuntime().availableProcessors();
        
        @Parameter( names = "--fromLayer", description = "The layer to start the optimization from (default: first layer in the tile specs data)", required = false )
        private int fromLayer = -1;

        @Parameter( names = "--toLayer", description = "The last layer to include in the optimization (default: last layer in the tile specs data)", required = false )
        private int toLayer = -1;
               
        @Parameter( names = "--skipLayers", description = "The layers ranges that will not be processed (default: none)", required = false )
        private String skippedLayers = "";

	}
	
	private OptimizeLayersElastic() {}
	
	private static Map< Integer, Map< Integer, CorrespondenceSpec > > parseCorrespondenceFiles(
			final List< String > fileUrls,
			final HashMap< String, Integer > tsUrlToLayerIds )
	{
		System.out.println( "Parsing correspondence files" );
		Map< Integer, Map< Integer, CorrespondenceSpec > > layersCorrs = new HashMap<Integer, Map<Integer,CorrespondenceSpec>>();
		
		for ( String fileUrl : fileUrls )
		{
			try
			{
				// Open and parse the json file
				final CorrespondenceSpec[] corr_data;
				try
				{
					final Gson gson = new Gson();
					URL url = new URL( fileUrl );
					corr_data = gson.fromJson( new InputStreamReader( url.openStream() ), CorrespondenceSpec[].class );
				}
				catch ( final MalformedURLException e )
				{
					System.err.println( "URL malformed." );
					e.printStackTrace( System.err );
					throw new RuntimeException( e );
				}
				catch ( final JsonSyntaxException e )
				{
					System.err.println( "JSON syntax malformed." );
					e.printStackTrace( System.err );
					throw new RuntimeException( e );
				}
				catch ( final Exception e )
				{
					e.printStackTrace( System.err );
					throw new RuntimeException( e );
				}
	
				for ( final CorrespondenceSpec corr : corr_data )
				{
					final int layer1Id;
					final int layer2Id;
					if ( tsUrlToLayerIds.containsKey( corr.url1 ) )
						layer1Id = tsUrlToLayerIds.get( corr.url1 );
					else
						layer1Id = readLayerFromFile( corr.url1 );
					if ( tsUrlToLayerIds.containsKey( corr.url2 ) )
						layer2Id = tsUrlToLayerIds.get( corr.url2 );
					else
						layer2Id = readLayerFromFile( corr.url2 );

					final Map< Integer, CorrespondenceSpec > innerMapping;
	
					if ( layersCorrs.containsKey( layer1Id ) )
					{
						innerMapping = layersCorrs.get( layer1Id );
					}
					else
					{
						innerMapping = new HashMap<Integer, CorrespondenceSpec>();
						layersCorrs.put( layer1Id, innerMapping );
					}
					// Assuming that no two files have the same correspondence spec url values
					innerMapping.put( layer2Id,  corr );
				}
			}
			catch (RuntimeException e)
			{
				System.err.println( "Error while reading file: " + fileUrl );
				e.printStackTrace( System.err );
				throw e;
			}
		}
		
		return layersCorrs;
	}

	private static int readLayerFromFile( String tsUrl )
	{
		final TileSpec[] tileSpecs = TileSpecUtils.readTileSpecFile( tsUrl );
		int layer = tileSpecs[0].layer;
		if ( layer == -1 )
			throw new RuntimeException( "Error: a tile spec json file (" + tsUrl + ") has a tilespec without a layer " );
		return layer;
	}
	

	private static Map< Integer, Map< Integer, CorrespondenceSpec > > parseCorrespondenceFiles(
			final List< String > fileUrls,
			final HashMap< String, Integer > tsUrlToLayerIds,
			final int threadsNum )
	{
		System.out.println( "Parsing correspondence files with " + threadsNum + " threads" );
		
		// Single thread case
		if ( threadsNum == 1 )
		{
			return parseCorrespondenceFiles( fileUrls, tsUrlToLayerIds );
		}
		
		final ConcurrentHashMap< Integer, Map< Integer, CorrespondenceSpec > > layersCorrs = new ConcurrentHashMap<Integer, Map<Integer,CorrespondenceSpec>>();
		
		// Initialize threads
		final ExecutorService exec = Executors.newFixedThreadPool( threadsNum );
		final ArrayList< Future< ? > > tasks = new ArrayList< Future< ? > >();

		final int filesPerThreadNum = fileUrls.size() / threadsNum;
		for ( int i = 0; i < threadsNum; i++ )
		{
			final int fromIndex = i * filesPerThreadNum;
			final int lastIndex;
			if ( i == threadsNum - 1 ) // lastThread
				lastIndex = fileUrls.size();
			else
				lastIndex = fromIndex + filesPerThreadNum;

			tasks.add( exec.submit( new Runnable() {
				
				@Override
				public void run() {
					// TODO Auto-generated method stub
					
					for ( int i = fromIndex; i < lastIndex; i++ )
					{
						final String fileUrl = fileUrls.get( i );
						try
						{
							// Open and parse the json file
							final CorrespondenceSpec[] corr_data;
							try
							{
								final Gson gson = new Gson();
								URL url = new URL( fileUrl );
								corr_data = gson.fromJson( new InputStreamReader( url.openStream() ), CorrespondenceSpec[].class );
							}
							catch ( final MalformedURLException e )
							{
								System.err.println( "URL malformed." );
								e.printStackTrace( System.err );
								throw new RuntimeException( e );
							}
							catch ( final JsonSyntaxException e )
							{
								System.err.println( "JSON syntax malformed." );
								e.printStackTrace( System.err );
								throw new RuntimeException( e );
							}
							catch ( final Exception e )
							{
								e.printStackTrace( System.err );
								throw new RuntimeException( e );
							}
				
							for ( final CorrespondenceSpec corr : corr_data )
							{
								final int layer1Id;
								final int layer2Id;
								if ( tsUrlToLayerIds.containsKey( corr.url1 ) )
									layer1Id = tsUrlToLayerIds.get( corr.url1 );
								else
									layer1Id = readLayerFromFile( corr.url1 );
								if ( tsUrlToLayerIds.containsKey( corr.url2 ) )
									layer2Id = tsUrlToLayerIds.get( corr.url2 );
								else
									layer2Id = readLayerFromFile( corr.url2 );

								Map< Integer, CorrespondenceSpec > innerMapping;
								
								if ( layersCorrs.containsKey( layer1Id ) )
								{
									innerMapping = layersCorrs.get( layer1Id );
								}
								else
								{
									innerMapping = new ConcurrentHashMap<Integer, CorrespondenceSpec>();
									Map<Integer, CorrespondenceSpec> curValue = layersCorrs.putIfAbsent( layer1Id, innerMapping );
									// If by the time we executed put, some other thread already put something instead 
									if ( curValue != null )
										innerMapping = layersCorrs.get( layer1Id );
								}
								// Assuming that no two files have the same correspondence spec url values
								innerMapping.put( layer2Id,  corr );
							}
						}
						catch (RuntimeException e)
						{
							System.err.println( "Error while reading file: " + fileUrl );
							e.printStackTrace( System.err );
							throw e;
						}
					}

				}
			}));

			
		}

		for ( Future< ? > task : tasks )
		{
			try {
				task.get();
			} catch (InterruptedException e) {
				exec.shutdownNow();
				e.printStackTrace();
			} catch (ExecutionException e) {
				exec.shutdownNow();
				e.printStackTrace();
			}
		}
		exec.shutdown();

		
		return layersCorrs;
	}

	
	private static ArrayList< Tile< ? > > createLayersModels( int layersNum, int desiredModelIndex )
	{
		System.out.println( "Creating default models for each layer" );

		/* create tiles and models for all layers */
		final ArrayList< Tile< ? > > tiles = new ArrayList< Tile< ? > >();
		for ( int i = 0; i < layersNum; ++i )
		{
			switch ( desiredModelIndex )
			{
			case 0:
				tiles.add( new Tile< TranslationModel2D >( new TranslationModel2D() ) );
				break;
			case 1:
				tiles.add( new Tile< RigidModel2D >( new RigidModel2D() ) );
				break;
			case 2:
				tiles.add( new Tile< SimilarityModel2D >( new SimilarityModel2D() ) );
				break;
			case 3:
				tiles.add( new Tile< AffineModel2D >( new AffineModel2D() ) );
				break;
			case 4:
				tiles.add( new Tile< HomographyModel2D >( new HomographyModel2D() ) );
				break;
			default:
				throw new RuntimeException( "Unknown desired model" );
			}
		}
		
		return tiles;
	}

	private static boolean compareArrays( float[] a, float[] b )
	{
		if ( a.length != b.length )
			return false;
		
		for ( int i = 0; i < a.length; i++ )
			// if ( a[i] != b[i] )
			if ( Math.abs( a[i] - b[i] ) > 2 * Math.ulp( b[i] ) )
				return false;
		
		return true;
	}
	
	/* Fixes the point match P1 vertices to point to the given vertices (same objects) */
	private static List< PointMatch > fixPointMatchVertices(
			List< PointMatch > pms,
			ArrayList< Vertex > vertices )
	{
		List< PointMatch > newPms = new ArrayList<PointMatch>( pms.size() );
		
		for ( final PointMatch pm : pms )
		{
			// Search for the given point match p1 point in the vertices list,
			// and if found, link the vertex instead of that point
			for ( final Vertex v : vertices )
			{
				if ( compareArrays( pm.getP1().getL(), v.getL() )  )
				{
					// Copy the new world values, in case there was a slight drift 
					for ( int i = 0; i < v.getW().length; i++ )
						v.getW()[ i ] = pm.getP1().getW()[ i ];
					
					PointMatch newPm = new PointMatch( v, pm.getP2(), pm.getWeights() );
					newPms.add( newPm );
				}
			}
		}
		
		return newPms;
	}

	private static void adjustTargetPointMatchVertices(
			final List< PointMatch > pms,
			final SpringMesh mesh )
	{		
		for ( final PointMatch pm : pms )
		{
			Point p = pm.getP2();
			mesh.applyInPlace( p.getL() );
			mesh.applyInPlace( p.getW() );
		}
	}

	
	private static ArrayList< SpringMesh > fixSubAllPointMatchVertices(
			final Params param,
			final Map< Integer, Map< Integer, CorrespondenceSpec > > layersCorrs,
			final int startLayer,
			final int endLayer )
	{
		System.out.println( "Fixing the point matches vertices between layers: " + startLayer + " - " + endLayer );

		final int meshWidth = ( int )Math.ceil( param.imageWidth * param.layerScale );
		final int meshHeight = ( int )Math.ceil( param.imageHeight * param.layerScale );
		
		final ArrayList< SpringMesh > meshes = new ArrayList< SpringMesh >( endLayer - startLayer + 1 );
		for ( int i = startLayer; i <= endLayer; ++i )
		{
			final SpringMesh singleMesh = new SpringMesh(
					param.resolutionSpringMesh,
					meshWidth,
					meshHeight,
					param.stiffnessSpringMesh,
					param.maxStretchSpringMesh * param.layerScale,
					param.dampSpringMesh ); 
			meshes.add( singleMesh );
			
			if ( layersCorrs.containsKey( i ) )
			{
				Map< Integer, CorrespondenceSpec > layerICorrs = layersCorrs.get( i );
				for ( CorrespondenceSpec corrspec : layerICorrs.values() )
				{
					final List< PointMatch > pms = corrspec.correspondencePointPairs;
					if ( pms != null )
					{
						final List< PointMatch > pmsFixed = fixPointMatchVertices( pms, singleMesh.getVertices() );
						corrspec.correspondencePointPairs = pmsFixed;
					}
					
				}
			}
		}

		return meshes;
	}

	private static ArrayList< SpringMesh > fixAllPointMatchVertices(
			final Params param,
			final Map< Integer, Map< Integer, CorrespondenceSpec > > layersCorrs,
			final int startLayer,
			final int endLayer,
			final int threadsNum )
	{
		System.out.println( "Fixing the point matches vertices with " + threadsNum + " threads" );
		
		// Single thread execution
		if ( threadsNum == 1 )
			return fixSubAllPointMatchVertices( param, layersCorrs, startLayer, endLayer );
		
		// Create thread pool and partition the layers between the threads
		final ExecutorService exec = Executors.newFixedThreadPool( threadsNum );
		final ArrayList< Future< ArrayList< SpringMesh > > > tasks = new ArrayList< Future< ArrayList< SpringMesh > > >();

		final int layersPerThreadNum = ( endLayer - startLayer + 1 ) / threadsNum;
		for ( int i = 0; i < threadsNum; i++ )
		{
			final int fromIndex = startLayer + i * layersPerThreadNum;
			final int lastIndex;
			if ( i == threadsNum - 1 ) // lastThread
				lastIndex = endLayer;
			else
				lastIndex = fromIndex + layersPerThreadNum - 1;

			tasks.add( exec.submit( new Callable< ArrayList< SpringMesh > >() {

				@Override
				public ArrayList<SpringMesh> call() throws Exception {
					return fixSubAllPointMatchVertices( param, layersCorrs, fromIndex, lastIndex );
				}
				
			}));
		}


		final ArrayList< SpringMesh > meshes = new ArrayList< SpringMesh >( endLayer - startLayer + 1 );

		for ( Future< ArrayList< SpringMesh > > task : tasks )
		{
			try {
				
				final ArrayList< SpringMesh > curMeshes = task.get();
				//System.out.println( "curMeshes size: " + curMeshes.size() );
				meshes.addAll( curMeshes );
			} catch (InterruptedException e) {
				exec.shutdownNow();
				e.printStackTrace();
			} catch (ExecutionException e) {
				exec.shutdownNow();
				e.printStackTrace();
			}
		}
		exec.shutdown();
		
		return meshes;
	}

	
	private static void matchLayers(
			final ArrayList< SpringMesh > meshes,
			final ArrayList< Tile< ? > > tiles,
			final TileConfiguration initMeshes,
			final Map< Integer, Map< Integer, CorrespondenceSpec > > layersCorrs,
			final List< Integer > fixedLayers,
			final int startLayer,
			final int endLayer,
			final Set<Integer> skippedLayers,
			final int maxDistance )
	{
		
		System.out.println( "Matching layers" );

		for ( int layerA = startLayer; layerA < endLayer; layerA++ )
		{
			//if ( skippedLayers.contains( layerA ) || !layersCorrs.containsKey( layerA ) )
			if ( skippedLayers.contains( layerA ) )
			{
				System.out.println( "Skipping optimization of layer " + layerA );
				continue;
			}
			//for ( Integer layerB : layersCorrs.get( layerA ).keySet() )
			//for ( int layerB = layerA + 1; layerB <= endLayer; layerB++ )
			for ( int layerB = layerA + 1; layerB <= layerA + maxDistance; layerB++ )
			{
				// We later both directions, so just do forward matching
				if ( layerB < layerA )
					continue;

				if ( layerB > endLayer )
					continue;
				
				if ( skippedLayers.contains( layerB ) )
				{
					System.out.println( "Skipping optimization of layer " + layerB );
					continue;
				}

				final boolean layer1Fixed = fixedLayers.contains( layerA );
				final boolean layer2Fixed = fixedLayers.contains( layerB );

				final CorrespondenceSpec corrspec12;
				final List< PointMatch > pm12;
				final CorrespondenceSpec corrspec21;
				final List< PointMatch > pm21;

				if ( !layersCorrs.containsKey( layerA ) || !layersCorrs.get( layerA ).containsKey( layerB ) )
				{
					corrspec12 = null;
					pm12 = null;
				}
				else
				{
					corrspec12 = layersCorrs.get( layerA ).get( layerB );
					pm12 = corrspec12.correspondencePointPairs;
				}

				if ( !layersCorrs.containsKey( layerB ) || !layersCorrs.get( layerB ).containsKey( layerA ) )
				{
					corrspec21 = null;
					pm21 = null;
				}
				else
				{
					corrspec21 = layersCorrs.get( layerB ).get( layerA );
					pm21 = corrspec21.correspondencePointPairs;
				}

				// Check if there are corresponding layers to this layer, otherwise skip
				if ( pm12 == null && pm21 == null )
					continue;

				
				
//				System.out.println( "Comparing layer " + layerA + " (fixed=" + layer1Fixed + ") to layer " +
//						layerB + " (fixed=" + layer2Fixed + ")" );
				
				if ( ( layer1Fixed && layer2Fixed ) )
					continue;

				final SpringMesh m1 = meshes.get( layerA - startLayer );
				final SpringMesh m2 = meshes.get( layerB - startLayer );

				// TODO: Load point matches
				
				final Tile< ? > t1 = tiles.get( layerA - startLayer );
				final Tile< ? > t2 = tiles.get( layerB - startLayer );

				final float springConstant  = 1.0f / ( layerB - layerA );
				

				if ( layer1Fixed )
					initMeshes.fixTile( t1 );
				else
				{
					if ( ( pm12 != null ) && ( pm12.size() > 1 ) )
					{
						//final List< PointMatch > pm12Fixed = fixPointMatchVertices( pm12, m1.getVertices() );
						
						for ( final PointMatch pm : pm12 )
						{
							final Vertex p1 = ( Vertex )pm.getP1();
							final Vertex p2 = new Vertex( pm.getP2() );
							p1.addSpring( p2, new Spring( 0, springConstant ) );
							m2.addPassiveVertex( p2 );
						}
						
						/*
						 * adding Tiles to the initialing TileConfiguration, adding a Tile
						 * multiple times does not harm because the TileConfiguration is
						 * backed by a Set. 
						 */
						if ( corrspec12.shouldConnect )
						{
							initMeshes.addTile( t1 );
							initMeshes.addTile( t2 );
							t1.connect( t2, pm12 );
						}

					}

				}

				if ( layer2Fixed )
					initMeshes.fixTile( t2 );
				else
				{
					if ( ( pm21 != null ) && ( pm21.size() > 1 ) )
					{
						//final List< PointMatch > pm21Fixed = fixPointMatchVertices( pm21, m2.getVertices() );

						for ( final PointMatch pm : pm21 )
						{
							final Vertex p1 = ( Vertex )pm.getP1();
							final Vertex p2 = new Vertex( pm.getP2() );
							p1.addSpring( p2, new Spring( 0, springConstant ) );
							m1.addPassiveVertex( p2 );
						}
						
						/*
						 * adding Tiles to the initialing TileConfiguration, adding a Tile
						 * multiple times does not harm because the TileConfiguration is
						 * backed by a Set. 
						 */
						if ( corrspec21.shouldConnect )
						{
							initMeshes.addTile( t1 );
							initMeshes.addTile( t2 );
							t2.connect( t1, pm21 );
						}
					}

				}
			
				System.out.println( layerA + " <> " + layerB + " spring constant = " + springConstant );

			}
			
		}

	}
	
	private static void matchLayers(
			final ArrayList< SpringMesh > meshes,
			final ArrayList< Tile< ? > > tiles,
			final TileConfiguration initMeshes,
			final Map< Integer, Map< Integer, CorrespondenceSpec > > layersCorrs,
			final List< Integer > fixedLayers,
			final int startLayer,
			final int endLayer,
			final Set<Integer> skippedLayers,
			final int maxDistance,
			final int threadsNum )
	{		
		System.out.println( "Matching layers with " + threadsNum + " threads" );

		if ( threadsNum == 1 )
		{
			matchLayers( meshes, tiles, initMeshes,
					layersCorrs, fixedLayers, startLayer, endLayer,
					skippedLayers, maxDistance );
			return;
		}
		
		// Initialize threads
		final ExecutorService exec = Executors.newFixedThreadPool( threadsNum );
		final ArrayList< Future< ? > > tasks = new ArrayList< Future< ? > >();

		final int layersPerThreadNum = (endLayer - startLayer + 1) / threadsNum;
		for ( int i = 0; i < threadsNum; i++ )
		{
			final int fromIndex = startLayer + i * layersPerThreadNum;
			final int lastIndex;
			if ( i == threadsNum - 1 ) // lastThread
				lastIndex = endLayer;
			else
				lastIndex = fromIndex + layersPerThreadNum;

			tasks.add( exec.submit( new Runnable() {
				
				@Override
				public void run() {
					for ( int layerA = fromIndex; layerA < lastIndex; layerA++ )
					{
						//if ( skippedLayers.contains( layerA ) || !layersCorrs.containsKey( layerA ) )
						if ( skippedLayers.contains( layerA ) )
						{
							System.out.println( "Skipping optimization of layer " + layerA );
							continue;
						}
						//for ( Integer layerB : layersCorrs.get( layerA ).keySet() )
						//for ( int layerB = layerA + 1; layerB <= endLayer; layerB++ )
						for ( int layerB = layerA + 1; layerB <= layerA + maxDistance; layerB++ )
						{
							// We later both directions, so just do forward matching
							if ( layerB < layerA )
								continue;

							if ( layerB > endLayer )
								continue;
							
							if ( skippedLayers.contains( layerB ) )
							{
								System.out.println( "Skipping optimization of layer " + layerB );
								continue;
							}

							final boolean layer1Fixed = fixedLayers.contains( layerA );
							final boolean layer2Fixed = fixedLayers.contains( layerB );

							final CorrespondenceSpec corrspec12;
							final List< PointMatch > pm12;
							final CorrespondenceSpec corrspec21;
							final List< PointMatch > pm21;

							if ( !layersCorrs.containsKey( layerA ) || !layersCorrs.get( layerA ).containsKey( layerB ) )
							{
								corrspec12 = null;
								pm12 = null;
							}
							else
							{
								corrspec12 = layersCorrs.get( layerA ).get( layerB );
								pm12 = corrspec12.correspondencePointPairs;
							}

							if ( !layersCorrs.containsKey( layerB ) || !layersCorrs.get( layerB ).containsKey( layerA ) )
							{
								corrspec21 = null;
								pm21 = null;
							}
							else
							{
								corrspec21 = layersCorrs.get( layerB ).get( layerA );
								pm21 = corrspec21.correspondencePointPairs;
							}

							// Check if there are corresponding layers to this layer, otherwise skip
							if ( pm12 == null && pm21 == null )
								continue;

							
							
//							System.out.println( "Comparing layer " + layerA + " (fixed=" + layer1Fixed + ") to layer " +
//									layerB + " (fixed=" + layer2Fixed + ")" );
							
							if ( ( layer1Fixed && layer2Fixed ) )
								continue;

							final SpringMesh m1 = meshes.get( layerA - startLayer );
							final SpringMesh m2 = meshes.get( layerB - startLayer );

							// TODO: Load point matches
							
							final Tile< ? > t1 = tiles.get( layerA - startLayer );
							final Tile< ? > t2 = tiles.get( layerB - startLayer );

							final float springConstant  = 1.0f / ( layerB - layerA );

							synchronized ( m1 )
							{
								synchronized ( m2 )
								{
							

									if ( layer1Fixed )
									{
										synchronized ( initMeshes )
										{
											initMeshes.fixTile( t1 );
										}
									}
									else
									{
										if ( ( pm12 != null ) && ( pm12.size() > 1 ) )
										{
											//final List< PointMatch > pm12Fixed = fixPointMatchVertices( pm12, m1.getVertices() );
											
											for ( final PointMatch pm : pm12 )
											{
												final Vertex p1 = ( Vertex )pm.getP1();
												final Vertex p2 = new Vertex( pm.getP2() );
												p1.addSpring( p2, new Spring( 0, springConstant ) );
												m2.addPassiveVertex( p2 );
											}
											
											/*
											 * adding Tiles to the initialing TileConfiguration, adding a Tile
											 * multiple times does not harm because the TileConfiguration is
											 * backed by a Set. 
											 */
											if ( corrspec12.shouldConnect )
											{
												synchronized ( initMeshes )
												{
													initMeshes.addTile( t1 );
													initMeshes.addTile( t2 );
												}
												t1.connect( t2, pm12 );
											}
		
										}
		
									}
		
									if ( layer2Fixed )
									{
										synchronized ( initMeshes )
										{
											initMeshes.fixTile( t2 );
										}
									}
									else
									{
										if ( ( pm21 != null ) && ( pm21.size() > 1 ) )
										{
											//final List< PointMatch > pm21Fixed = fixPointMatchVertices( pm21, m2.getVertices() );
		
											for ( final PointMatch pm : pm21 )
											{
												final Vertex p1 = ( Vertex )pm.getP1();
												final Vertex p2 = new Vertex( pm.getP2() );
												p1.addSpring( p2, new Spring( 0, springConstant ) );
												m1.addPassiveVertex( p2 );
											}
											
											/*
											 * adding Tiles to the initialing TileConfiguration, adding a Tile
											 * multiple times does not harm because the TileConfiguration is
											 * backed by a Set. 
											 */
											if ( corrspec21.shouldConnect )
											{
												synchronized ( initMeshes )
												{
													initMeshes.addTile( t1 );
													initMeshes.addTile( t2 );
												}
												t2.connect( t1, pm21 );
											}
										}
		
									}
								}// end synchronized ( m2 )
							}// end synchronized ( m1 )
						
							System.out.println( layerA + " <> " + layerB + " spring constant = " + springConstant );

						}
						
					}
				}
			}));
		}
		
		for ( Future< ? > task : tasks )
		{
			try {
				task.get();
			} catch (InterruptedException e) {
				exec.shutdownNow();
				e.printStackTrace();
			} catch (ExecutionException e) {
				exec.shutdownNow();
				e.printStackTrace();
			}
		}
		exec.shutdown();

	}
	
	/**
	 * Optimizes the layers using elastic transformation,
	 * and updates the transformations of the tile-specs in the given layerTs.
	 * 
	 * @param param
	 * @param layersTs
	 * @param layersCorrs
	 * @param fixedLayers
	 * @param startLayer
	 * @param endLayer
	 * @param startX
	 * @param startY
	 */
	private static void optimizeElastic(
			final Params param,
			final HashMap< Integer, List< TileSpec > > layersTs,
			final Map< Integer, Map< Integer, CorrespondenceSpec > > layersCorrs,
			final List< Integer > fixedLayers,
			final int startLayer,
			final int endLayer,
			final int startX,
			final int startY,
			final Set<Integer> skippedLayers,
			final int maxDistance )
	{
		final ArrayList< Tile< ? > > tiles = createLayersModels( endLayer - startLayer + 1, param.modelIndex );
		
		/* Initialization */
		final TileConfiguration initMeshes = new TileConfiguration();
		initMeshes.setThreadsNum( param.numThreads );
				
		final ArrayList< SpringMesh > meshes = fixAllPointMatchVertices(
				param, layersCorrs, startLayer, endLayer, param.numThreads );

		matchLayers( meshes, tiles, initMeshes,
				layersCorrs, fixedLayers, startLayer, endLayer,
				skippedLayers, maxDistance, param.numThreads );
		

		/* pre-align by optimizing a piecewise linear model */
		System.out.println( "Pre-aligning by optimizing piecewise linear model" );
		try
		{
			initMeshes.optimize(
					param.maxEpsilon * param.layerScale,
					param.maxIterationsSpringMesh,
					param.maxPlateauwidthSpringMesh );
		}
		catch ( Exception e )
		{
			throw new RuntimeException( e );
		}
		System.out.println( "Initializing meshes using models" );
		for ( int i = startLayer; i <= endLayer; ++i )
			meshes.get( i - startLayer ).init( tiles.get( i - startLayer ).getModel() );
		
		/* optimize the meshes */
		try
		{
			final long t0 = System.currentTimeMillis();
			System.out.println( "Optimizing spring meshes..." );
			
			if ( param.useLegacyOptimizer )
			{
				System.out.println( "  ...using legacy optimizer...");
				SpringMesh.optimizeMeshes2(
						meshes,
						param.maxEpsilon * param.layerScale,
						param.maxIterationsSpringMesh,
						param.maxPlateauwidthSpringMesh );
			}
			else
			{
				SpringMesh.optimizeMeshes(
						meshes,
						param.maxEpsilon * param.layerScale,
						param.maxIterationsSpringMesh,
						param.maxPlateauwidthSpringMesh );
			}

			System.out.println( "Done optimizing spring meshes. Took " + (System.currentTimeMillis() - t0) + " ms");
			
		}
		catch ( final NotEnoughDataPointsException e )
		{
			System.err.println( "There were not enough data points to get the spring mesh optimizing." );
			e.printStackTrace();
			throw new RuntimeException( e );
		}
		
		// Find current bounding box of tilespecs
		
		
		/* translate relative to bounding box */
		final int boxX = startX;
		final int boxY = startY;
		for ( final SpringMesh mesh : meshes )
		{
			for ( final PointMatch pm : mesh.getVA().keySet() )
			{
				final Point p1 = pm.getP1();
				final Point p2 = pm.getP2();
				final float[] l = p1.getL();
				final float[] w = p2.getW();
				l[ 0 ] = l[ 0 ] / param.layerScale + boxX;
				l[ 1 ] = l[ 1 ] / param.layerScale + boxY;
				w[ 0 ] = w[ 0 ] / param.layerScale + boxX;
				w[ 1 ] = w[ 1 ] / param.layerScale + boxY;
			}
		}

		
		// Iterate the layers, and add the mesh transform for each tile
		for ( int i = startLayer; i <= endLayer; ++i )
		{
			if ( skippedLayers.contains( i ) )
			{
				System.out.println( "Skipping saving after optimization of layer " + i );
				continue;
			}
			
			final SpringMesh mesh = meshes.get( i - startLayer );
			final List< TileSpec > layer = layersTs.get( i );
			
			System.out.println( "Updating tiles in layer " + i );
			
			
			
			
			final RestrictedMovingLeastSquaresTransform2 rmlt = new RestrictedMovingLeastSquaresTransform2();
			try {
				rmlt.setModel( AffineModel2D.class );
				rmlt.setAlpha( 2.0f );
				rmlt.setMatches( mesh.getVA().keySet() );
				rmlt.setRadius( rmlt.computeDefaultRadius() );


				for ( TileSpec ts : layer )
				{
					/*
					final MovingLeastSquaresTransform2 mlt = new MovingLeastSquaresTransform2();
					mlt.setModel( AffineModel2D.class );
					mlt.setAlpha( 2.0f );
					mlt.setMatches( mesh.getVA().keySet() );
					*/
					final RestrictedMovingLeastSquaresTransform2 boundedRMLT = rmlt.boundToBoundingBox( ts.bbox );
					
				    Transform addedTransform = new Transform();				    
				    addedTransform.className = boundedRMLT.getClass().getCanonicalName().toString();
				    addedTransform.dataString = boundedRMLT.toDataString();
				    
					ArrayList< Transform > outTransforms = new ArrayList< Transform >(Arrays.asList(ts.transforms));
					outTransforms.add(addedTransform);
					ts.transforms = outTransforms.toArray(ts.transforms);

					// bounding box after transformations are applied [left, right, top, bottom] possibly with extra entries for [front, back, etc.]
					final float[] meshMin = new float[ 2 ];
					final float[] meshMax = new float[ 2 ];
					mesh.bounds( meshMin, meshMax );			
					ts.bbox = new float[] {
							/*meshMin[0] * param.layerScale,
							meshMax[0] * param.layerScale,
							meshMin[1] * param.layerScale,
							meshMax[1] * param.layerScale */
							meshMin[0],
							meshMax[0],
							meshMin[1],
							meshMax[1]
						};
				}

			}
			catch ( final Exception e )
			{
				System.out.println( "Error applying transform to tile in layer " + i + "." );
				e.printStackTrace();
			}

			
			
		}
		
	}


	public static void main( final String[] args )
	{
		
		final Params params = new Params();
		
		try
        {
			final JCommander jc = new JCommander( params, args );
        	if ( params.help )
            {
        		jc.usage();
                return;
            }
        }
        catch ( final Exception e )
        {
        	e.printStackTrace();
            final JCommander jc = new JCommander( params );
        	jc.setProgramName( "java [-options] -cp render.jar org.janelia.alignment.RenderTile" );
        	jc.usage(); 
        	return;
        }
		
		Set<Integer> skippedLayers = Utils.parseRange( params.skippedLayers );
		
		List< String > actualTileSpecFiles;
		if ( params.tileSpecFiles.size() == 1 )
			// It might be a non-json file that contains a list of
			actualTileSpecFiles = Utils.getListFromFile( params.tileSpecFiles.get( 0 ) );
		else
			actualTileSpecFiles = params.tileSpecFiles;
		
		System.out.println( "Reading tilespecs" );

		// Load and parse tile spec files
		List< String > relevantTileSpecFiles = new ArrayList<String>();
		final HashMap< Integer, List< TileSpec > > layersTs = new HashMap<Integer, List<TileSpec>>();
		final HashMap< String, Integer > tsUrlToLayerIds = new HashMap<String, Integer>();
		final HashMap< Integer, String > layerIdToTsUrl = new HashMap<Integer, String>();
		for ( final String tsUrl : actualTileSpecFiles )
		{
			final TileSpec[] tileSpecs = TileSpecUtils.readTileSpecFile( tsUrl );
			int layer = tileSpecs[0].layer;
			if ( layer == -1 )
				throw new RuntimeException( "Error: a tile spec json file (" + tsUrl + ") has a tilespec without a layer " );
			
			if ( skippedLayers.contains( layer ) ) // No need to add skipped layers
				continue;

			relevantTileSpecFiles.add( tsUrl );
			layersTs.put( layer, Arrays.asList( tileSpecs ) );
			tsUrlToLayerIds.put( tsUrl, layer );
			layerIdToTsUrl.put( layer, tsUrl );
		}

		List< String > actualCorrFiles;
		if ( params.corrFiles.size() == 1 )
			// It might be a non-json file that contains a list of
			actualCorrFiles = Utils.getListFromFile( params.corrFiles.get( 0 ) );
		else
			actualCorrFiles = params.corrFiles;

		// Load and parse correspondence spec files
		final Map< Integer, Map< Integer, CorrespondenceSpec > > layersCorrs;
		layersCorrs = parseCorrespondenceFiles( actualCorrFiles, tsUrlToLayerIds, params.numThreads );

		// Find bounding box
		System.out.println( "Finding bounding box" );
		final TileSpecsImage entireImage = TileSpecsImage.createImageFromFiles( relevantTileSpecFiles );
		final BoundingBox bbox = entireImage.getBoundingBox();
		
		int firstLayer = bbox.getStartPoint().getZ();
		if (( params.fromLayer != -1 ) && ( params.fromLayer >= firstLayer ))
			firstLayer = params.fromLayer;
		int lastLayer = bbox.getEndPoint().getZ();
		if (( params.toLayer != -1 ) && ( params.toLayer <= lastLayer ))
			lastLayer = params.toLayer;
		
		// Remove non existent fixed layers
		Iterator< Integer > fixedIt = params.fixedLayers.iterator();
		while ( fixedIt.hasNext() ) {
			int fixedLayer = fixedIt.next();
			if ( ( fixedLayer < firstLayer ) ||
				 ( fixedLayer > lastLayer ) ||
				 ( skippedLayers.contains( fixedLayer ) ) ) {
				fixedIt.remove();
			}
		}
		if ( params.fixedLayers.size() == 0 ) {
			params.fixedLayers.add( firstLayer );
		}
		
		// Optimze
		optimizeElastic(
			params, layersTs, layersCorrs,
			params.fixedLayers,
			firstLayer, lastLayer,
			bbox.getStartPoint().getX(), bbox.getStartPoint().getY(),
			skippedLayers,
			params.maxLayersDistance );

		// Save new tilespecs
		System.out.println( "Optimization complete. Generating tile transforms.");

		// Iterate through the layers and output the new tile specs
		for ( int layer = firstLayer; layer <= lastLayer; layer++ )
		{
			if ( skippedLayers.contains( layer ) )
				continue;
			
			String jsonFilename = layerIdToTsUrl.get( layer );
			String baseFilename = jsonFilename.substring( jsonFilename.lastIndexOf( '/' ) );
			
			String layerString = String.format( "%04d", layer );
			System.out.println( "Writing layer " + layerString );
			final File targetFile = new File( params.targetDir, baseFilename );
			final List< TileSpec > layerOutTiles = layersTs.get( layer );
			try {
				Writer writer = new FileWriter(targetFile);
		        //Gson gson = new GsonBuilder().create();
		        Gson gson = new GsonBuilder().setPrettyPrinting().create();
		        gson.toJson(layerOutTiles, writer);
		        writer.close();
		    }
			catch ( final IOException e )
			{
				System.err.println( "Error writing JSON file: " + targetFile.getAbsolutePath() );
				e.printStackTrace( System.err );
			}

		}
		

		System.out.println( "Done." );
	}

}