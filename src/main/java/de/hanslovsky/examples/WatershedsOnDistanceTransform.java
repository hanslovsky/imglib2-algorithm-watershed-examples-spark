package de.hanslovsky.examples;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleBiFunction;
import java.util.stream.IntStream;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.storage.StorageLevel;
import org.janelia.saalfeldlab.n5.AbstractDataBlock;
import org.janelia.saalfeldlab.n5.CompressionType;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.imglib2.N5CellLoader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.bigcat.viewer.viewer3d.util.HashWrapper;
import de.hanslovsky.examples.kryo.Registrator;
import de.hanslovsky.examples.pipeline.Extend;
import de.hanslovsky.examples.pipeline.RAIFromLoader;
import gnu.trove.iterator.TLongLongIterator;
import gnu.trove.map.hash.TLongLongHashMap;
import gnu.trove.map.hash.TObjectLongHashMap;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.morphology.watershed.PriorityQueueFactory;
import net.imglib2.algorithm.morphology.watershed.PriorityQueueFastUtil;
import net.imglib2.img.basictypeaccess.array.ArrayDataAccess;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.ConstantUtils;
import net.imglib2.view.Views;
import net.imglib2.view.composite.Composite;
import scala.Tuple2;

public class WatershedsOnDistanceTransform
{

	private static final Logger LOG = LoggerFactory.getLogger( MethodHandles.lookup().lookupClass() );

	public static < T extends RealType< T > & NativeType< T >, A extends ArrayDataAccess< A > > JavaPairRDD< long[], Tuple2< RandomAccessibleInterval< UnsignedLongType >, Long > > flood(
			final JavaSparkContext sc,
			final String group,
			final String dataset,
			final CellGrid watershedsGrid,
			final T extension,
			final A access,
			final boolean processBySlice,
			final Predicate< T > threshold,
			final ToDoubleBiFunction< T, T > dist,
			final PriorityQueueFactory factory,
			final int[] context )
	{
		final List< HashWrapper< long[] > > offsets = Util.collectAllOffsets(
				watershedsGrid.getImgDimensions(),
				IntStream.range( 0, watershedsGrid.numDimensions() ).map( d -> watershedsGrid.cellDimension( d ) ).toArray(),
				a -> HashWrapper.longArray( a ) );

		final JavaPairRDD< HashWrapper< long[] >, RandomAccessible< T > > affinities = sc
				.parallelize( offsets )
				.mapToPair( new RAIFromLoader< T >( sc, group, dataset ) )
				.mapValues( new Extend<>( sc.broadcast( extension ) ) );
		return flood( sc, affinities, watershedsGrid, processBySlice, threshold, dist, factory, context );
	}

	public static < T extends RealType< T > & NativeType< T > > JavaPairRDD< long[], Tuple2< RandomAccessibleInterval< UnsignedLongType >, Long > > flood(
			final JavaSparkContext sc,
			final JavaPairRDD< HashWrapper< long[] >, RandomAccessible< T > > affinities,
			final CellGrid watershedsGrid,
			final boolean processBySlice,
			final Predicate< T > threshold,
			final ToDoubleBiFunction< T, T > dist,
			final PriorityQueueFactory factory,
			final int[] context )
	{
		final RunWatershedsOnDistanceTransform< T, UnsignedLongType > rw = new RunWatershedsOnDistanceTransform<>( sc, processBySlice, threshold, dist, new UnsignedLongType(), factory );
		final JavaPairRDD< long[], Tuple2< RandomAccessibleInterval< UnsignedLongType >, Long > > mapped = affinities
				.mapToPair( new ToInterval<>( sc, watershedsGrid, context ) )
				.mapValues( rw );
		return mapped;
	}

	public static void main( final String[] args ) throws IOException
	{

		final String n5Path = args[ 0 ];
		final String n5Dataset = args[ 1 ];
		final String n5TargetNonBlocked = args[ 2 ];
		final String n5Target = args[ 3 ];
		final double threshold = Double.parseDouble( args[ 4 ] );
		final int[] wsBlockSize = Arrays.stream( args[ 5 ].split( "," ) ).mapToInt( Integer::parseInt ).toArray();
		final int[] context = Arrays.stream( args[ 6 ].split( "," ) ).mapToInt( Integer::parseInt ).toArray();
		final double minVal = Double.parseDouble( args[ 7 ] );

		if ( wsBlockSize.length != 3 )
			throw new IllegalArgumentException( "block size array length is not 3! " + wsBlockSize.length + " " + Arrays.toString( wsBlockSize ) );

		final boolean processSliceBySlice = false;

		final PriorityQueueFactory queueFactory = new PriorityQueueFastUtil.Factory();
		// HierarchicalPriorityQueueQuantized.Factory( 256, -maxVal, -minVal );

		final N5FSReader reader = new N5FSReader( n5Path );
		final DatasetAttributes attrs = reader.getDatasetAttributes( n5Dataset );
		final CellGrid affinitiesGrid = new CellGrid( attrs.getDimensions(), attrs.getBlockSize() );
		final N5CellLoader< FloatType > affinitiesLoader = new N5CellLoader<>( reader, n5Dataset, attrs.getBlockSize() );

		final CellGrid wsGrid = new CellGrid( Arrays.stream( attrs.getDimensions() ).limit( 3 ).toArray(), wsBlockSize );

		final SparkConf conf = new SparkConf()
				.setAppName( MethodHandles.lookup().lookupClass().getName() )
				//				.setMaster( "local[*]" )
				.set( "spark.serializer", "org.apache.spark.serializer.KryoSerializer" )
				.set( "spark.kryo.registrator", Registrator.class.getName() )
				;

		final JavaSparkContext sc = new JavaSparkContext( conf );
		sc.setLogLevel( "ERROR" );

		final Predicate< FloatType > thresholdPredicate = new ThresholdPredicate( threshold );
		// c -> c.getRealDouble() > threshold;

		final ToDoubleBiFunction< FloatType, FloatType > dist = new Distance( minVal );
		// ( c, r ) -> c.getRealDouble() > minVal ? -c.getRealDouble() :
		// Double.POSITIVE_INFINITY;

		final JavaPairRDD< long[], Tuple2< RandomAccessibleInterval< UnsignedLongType >, Long > > data = flood(
				sc,
				n5Path,
				n5Dataset,
				wsGrid,
				new FloatType( 0.0f ),
				new FloatArray( 1 ),
				processSliceBySlice,
				thresholdPredicate,
				dist,
				queueFactory,
				context );
		data.persist( StorageLevel.DISK_ONLY() ).count();

		final N5FSWriter writer = new N5FSWriter( n5Path );
		writer.createDataset( n5Target, wsGrid.getImgDimensions(), wsBlockSize, DataType.UINT64, CompressionType.GZIP );
		writer.createDataset( n5TargetNonBlocked, wsGrid.getImgDimensions(), wsBlockSize, DataType.UINT64, CompressionType.GZIP );
		final DatasetAttributes wsAttrs = writer.getDatasetAttributes( n5Target );
		final Broadcast< CellGrid > wsGridBC = sc.broadcast( wsGrid );

		final List< Tuple2< long[], Long > > counts = data
				.map( t -> new Tuple2<>( t._1(), t._2()._2() ) )
				.collect();

		long totalCount = 0;
		final TObjectLongHashMap< HashWrapper< long[] > > offsetMap = new TObjectLongHashMap<>();
		for ( final Tuple2< long[], Long > count : counts )
		{
			offsetMap.put( HashWrapper.longArray( count._1() ), totalCount );
			totalCount += count._2();
		}
		LOG.info( "Got {} labels ({} {})", totalCount, 1.0 * totalCount / Integer.MAX_VALUE, 1.0 * totalCount / Long.MAX_VALUE );
		final Broadcast< Tuple2< long[][], long[] > > offsetMapBC = sc.broadcast( new Tuple2<>(
				Arrays.stream( offsetMap.keys() ).map( hw -> ( ( HashWrapper< long[] > ) hw ).getData() ).toArray( long[][]::new ),
				offsetMap.values() ) );


		final JavaPairRDD< long[], RandomAccessibleInterval< UnsignedLongType > > remapped = data
				.mapToPair( t -> {
					final RandomAccessibleInterval< UnsignedLongType > source = t._2()._1();
					final long[] key = t._1();
					final long[][] keys = offsetMapBC.getValue()._1();
					final long[] vals = offsetMapBC.getValue()._2();
					long offset = -1;
					for ( int i = 0; i < keys.length; ++i )
						if ( Arrays.equals( key, keys[ i ] ) )
							offset = vals[i];
					if ( offset == -1 )
						throw new RuntimeException( "Did not find label offset for " + Arrays.toString( key ) );
					final UnsignedLongType zero = new UnsignedLongType( 0 );
					for ( final UnsignedLongType s : Views.flatIterable( source ) )
						if ( !zero.valueEquals( s ) )
							s.setInteger( s.get() + offset );
					return new Tuple2<>( t._1(), source );
				} )
				.persist( StorageLevel.DISK_ONLY() );
		remapped.count();
		data.unpersist();

//		System.out.println( "CHECKING DUPLICATE LABELS!" );
//		remapped
//		.cartesian( remapped )
//		.filter( input -> {
//			if ( Arrays.equals( input._1()._1(), input._2()._1() ) )
//				return false;
//			final TLongHashSet labels = new TLongHashSet();
//			for ( final UnsignedLongType l : Views.flatIterable( input._1()._2() ) )
//				if ( l.get() != 0 )
//					labels.add( l.get() );
//
//			for ( final UnsignedLongType l : Views.flatIterable( input._2()._2() ) )
//				if ( labels.contains( l.get() ) && l.get() != 0 )
//					return true;
//
//			return false;
//		} )
//		.map( t -> {
//			System.out.println( "DUPLICATE LABELS IN " + Arrays.toString( t._1()._1() ) + " " + Arrays.toString( t._2()._1() ) );
//			return t;
//		} )
//		.count();
//		System.out.println( "DONE CHECKING DUPLICATES" );
		// NO DUPLICATE LABELS FOUND!

//		remapped
//		.filter( rai -> Arrays.equals( Intervals.minAsLongArray( rai ), new long[] { 225, 225, 225 } ) )
//		.map( rai -> {
//			final N5FSWriter firstBlockWriter = new N5FSWriter( "first-block" );
//			final String firstBlockDS = "fb";
//			firstBlockWriter.createDataset( firstBlockDS, Intervals.dimensionsAsLongArray( rai ), Intervals.dimensionsAsIntArray( rai ), DataType.UINT64, CompressionType.RAW );
//			N5Utils.saveBlock( rai, firstBlockWriter, firstBlockDS, new long[] { 0, 0, 0 } );
//
//			return true;
//		} ).count();

//		final TLongHashSet firstLabels = new TLongHashSet();
//		final long[] firstMin = Intervals.minAsLongArray( remapped.first() );
//		for ( final UnsignedLongType label : Views.flatIterable( remapped.first() ) )
//			firstLabels.add( label.get() );
//
//		remapped.filter( rai -> {
//			for ( int d = 0; d < firstMin.length; ++d )
//				if ( firstMin[ d ] != rai.min( d ) )
//					return true;
//			return false;
//		} ).map( rai -> {
//			for ( final UnsignedLongType label : Views.flatIterable( rai ) )
//				if ( firstLabels.contains( label.get() ) )
//				{
//					System.out.println( "LABEL " + label + " CONTAINED IN " + Arrays.toString( Intervals.minAsLongArray( rai ) ) );
//					return true;
//				}
//			return false;
//		} ).count();

		final TLongLongHashMap sparseUFParents = new TLongLongHashMap();
		final TLongLongHashMap sparseUFRanks= new TLongLongHashMap();
		final UnionFindSparse sparseUnionFind = new UnionFindSparse( sparseUFParents, sparseUFRanks, 0 );

		// iterate over all dimensions and merge based on single pixel overlap
		for ( int d = 0; d < wsGrid.numDimensions(); ++d ) {

			final int finalD = d;
			final String n5TargetUpper = n5Target + "-upper-" + d;
			final String n5TargetLower = n5Target + "-lower-" + d;

			// size along current dimension is equal to number of cells in this
			// dimension (also, cell size along this dimension is 1)
			final long[] imgSize = wsGrid.getImgDimensions();
			imgSize[ d ] = wsGrid.gridDimension( d );
			final int[] blockSize = new int[ wsGrid.numDimensions() ];
			for ( int k = 0; k < blockSize.length; ++k )
				blockSize[ k ] = k == d ? 1 : wsGrid.cellDimension( k );

			writer.createDataset( n5TargetUpper, imgSize, blockSize, DataType.UINT64, CompressionType.RAW );
			writer.createDataset( n5TargetLower, imgSize, blockSize, DataType.UINT64, CompressionType.RAW );
			remapped.map( t -> {
				final CellGrid localGrid = wsGridBC.getValue();
				final N5FSWriter localWriter = new N5FSWriter( n5Path );
				final RandomAccessibleInterval< UnsignedLongType > rai = t._2();
				final long[] pos = t._1();
				final long[] min = pos.clone();
				final long[] max = new long[ min.length ];
				for ( int k = 0; k < max.length; ++k )
					max[ k ] = Math.min( min[ k ] + localGrid.cellDimension( k ), localGrid.imgDimension( k ) ) - 1;
				max[ finalD ] += 1;

				// create view of last plane along finalD within interval
				min[ finalD ] = max[ finalD ];
				final FinalInterval lastPlaneInterval = new FinalInterval( min, max );
//				System.out.println( "last min max " + Arrays.toString( min ) + " " + Arrays.toString( max ) );
				final RandomAccessibleInterval< UnsignedLongType > upper;
				if ( max[ finalD ] > rai.max( finalD ) )
					upper = Views.zeroMin( ConstantUtils.constantRandomAccessibleInterval( new UnsignedLongType(), min.length, lastPlaneInterval ) );
				else
					upper = Views.zeroMin( Views.interval( rai, lastPlaneInterval ) );

				// create view of first plane along finalD within interval
				min[ finalD ] = pos[ finalD ];
				max[ finalD ] = min[ finalD ];
				final FinalInterval firstPlaneInterval = new FinalInterval( min, max );
//				System.out.println( "first min max " + Arrays.toString( min ) + " " + Arrays.toString( max ) );
				final RandomAccessibleInterval< UnsignedLongType > lower = Views.zeroMin( Views.interval( rai, firstPlaneInterval ) );

				final long[] cellPos = new long[ pos.length ];
				localGrid.getCellPosition( pos, cellPos );

//				System.out.println( "WRITING AT " + Arrays.toString( cellPos ) + " " + Arrays.toString( pos ) + " " + Arrays.toString( Intervals.minAsLongArray( rai ) ) );
				N5Utils.saveBlock( upper, localWriter, n5TargetUpper, cellPos );
				N5Utils.saveBlock( lower, localWriter, n5TargetLower, cellPos );

				return true;
			} ).count();

			final Broadcast< DatasetAttributes > upperAttributesBC = sc.broadcast( writer.getDatasetAttributes( n5TargetUpper ) );
			final Broadcast< DatasetAttributes > lowerAttributesBC = sc.broadcast( writer.getDatasetAttributes( n5TargetLower ) );

			final List< Tuple2< long[], long[] > > assignments = remapped
					.keys()
					.map( offset -> {
						final N5FSWriter localWriter = new N5FSWriter( n5Path );
						final TLongLongHashMap parents = new TLongLongHashMap();
						final TLongLongHashMap ranks = new TLongLongHashMap();
						final UnionFindSparse localUnionFind = new UnionFindSparse( parents, ranks, 0 );
						final CellGrid grid = wsGridBC.getValue();
						//						if ( offset[ finalD ] + grid.cellDimension( finalD ) < grid.imgDimension( finalD ) )
						//						{
						final long[] cellPos = new long[ offset.length ];
						grid.getCellPosition( offset, cellPos );
						final long[] upperCellPos = cellPos.clone();
						upperCellPos[ finalD ] += 1;
						if ( upperCellPos[ finalD ] < grid.gridDimension( finalD ) )
						{

							//							System.out.println( "READING AT " + Arrays.toString( cellPos ) + " " + Arrays.toString( upperCellPos ) );
							// will need lower plane from block with higher
							// index and vice versa
							final AbstractDataBlock< long[] > upperPlaneInLowerCell = ( AbstractDataBlock< long[] > ) localWriter.readBlock( n5TargetUpper, upperAttributesBC.getValue(), cellPos );
							final AbstractDataBlock< long[] > lowerPlaneInUpperCell = ( AbstractDataBlock< long[] > ) localWriter.readBlock( n5TargetLower, lowerAttributesBC.getValue(), upperCellPos );

							final long[] upperData = upperPlaneInLowerCell.getData();
							final long[] lowerData = lowerPlaneInUpperCell.getData();

							final TLongLongHashMap forwardAssignments = new TLongLongHashMap();
							final TLongLongHashMap backwardAssignments = new TLongLongHashMap();
							final TLongLongHashMap assignmentCounts = new TLongLongHashMap();

							for ( int i = 0; i < upperData.length; ++i )
							{
								final long ud = upperData[ i ];
								final long ld = lowerData[ i ];
								//									if ( ud == 0 || ld == 0 )
								//										System.out.println( "WHY ZERO? " + ud + " " + ld );
								if ( ud != 0 && ld != 0 )
									if ( forwardAssignments.contains( ud ) && backwardAssignments.contains( ld ) )
									{
										if ( forwardAssignments.get( ud ) != ld || backwardAssignments.get( ld ) != ud )
										{
											forwardAssignments.put( ud, -1 );
											backwardAssignments.put( ld, -1 );
										}
										else
										{
											assignmentCounts.put( ud, assignmentCounts.get( ud ) + 1 );
											assignmentCounts.put( ld, assignmentCounts.get( ld ) + 1 );
										}
									}
									else
									{
										forwardAssignments.put( ud, ld );
										backwardAssignments.put( ld, ud );
										assignmentCounts.put( ud, 1 );
									}
							}
							for ( final TLongLongIterator it = forwardAssignments.iterator(); it.hasNext(); )
							{
								it.advance();
								final long k = it.key();
								final long v = it.value();
								if ( v != -1 && backwardAssignments.get( v ) == k )
									localUnionFind.join( localUnionFind.findRoot( k ), localUnionFind.findRoot( v ) );
							}
						}
						//						}
						return new Tuple2<>( parents.keys(), parents.values() );
					} )
					.collect();

//			System.out.println( "DIMENSION " + d );
			int zeroMappingsCount = 0;

			for ( final Tuple2< long[], long[] > assignment : assignments )
			{
				final long[] keys = assignment._1();
				final long[] vals = assignment._2();
				if ( keys.length == 0 )
					++zeroMappingsCount;
				for ( int i = 0; i < keys.length; ++i )
					sparseUnionFind.join( sparseUnionFind.findRoot( keys[i] ), sparseUnionFind.findRoot( vals[i] ) );
			}
//			System.out.println( "zeroMappingsCount " + zeroMappingsCount );
//			System.out.println();

		}

		final int setCount = sparseUnionFind.setCount();
		final Broadcast< Tuple2< long[], long[] > > parentsBC = sc.broadcast( new Tuple2<>( sparseUFParents.keys(), sparseUFParents.values() ) );
		final Broadcast< Tuple2< long[], long[] > > ranksBC = sc.broadcast( new Tuple2<>( sparseUFRanks.keys(), sparseUFRanks.values() ) );
//		System.out.println( "SPARSE PARENTS ARE " + sparseUFParents );


		remapped
		.map( t -> {
			final RandomAccessibleInterval< UnsignedLongType > rai = t._2();
			final long[] blockMin = t._1().clone();
			final long[] blockMax = new long[ blockMin.length ];
			for ( int d = 0; d < blockMin.length; ++d )
				blockMax[ d ] = Math.min( blockMin[ d ] + wsGridBC.getValue().cellDimension( d ), wsGridBC.getValue().imgDimension( d ) ) - 1;
			final RandomAccessibleInterval< UnsignedLongType > target = Views.interval( rai, new FinalInterval( blockMin, blockMax ) );
			return target;
		} )
		.map( new WriteN5<>( sc, n5Path, n5TargetNonBlocked, wsAttrs.getBlockSize() ) )
		.count();


		remapped
		.map( tuple -> {
			final long[] blockMin = tuple._1().clone();
			final long[] blockMax = new long[ blockMin.length ];
			for ( int d = 0; d < blockMin.length; ++d )
				blockMax[ d ] = Math.min( blockMin[ d ] + wsGridBC.getValue().cellDimension( d ), wsGridBC.getValue().imgDimension( d ) ) - 1;
			final RandomAccessibleInterval< UnsignedLongType > rai = Views.interval( tuple._2(), new FinalInterval( blockMin, blockMax ) );
			final UnionFindSparse uf = new UnionFindSparse( new TLongLongHashMap( parentsBC.getValue()._1(), parentsBC.getValue()._2() ), new TLongLongHashMap( ranksBC.getValue()._1(), ranksBC.getValue()._2() ), setCount );
			//					System.out.println( "JOINING HERE: " + parentsBC.getValue() );

			for ( final UnsignedLongType t : Views.flatIterable( rai ) )
				if ( t.getIntegerLong() != 0 )
					t.set( uf.findRoot( t.getIntegerLong() ) );
			return rai;
		} )
		.map( new WriteN5<>( sc, n5Path, n5Target, wsAttrs.getBlockSize() ) )
		.count();



		sc.close();
	}

	public static class ToInterval< V > implements PairFunction< Tuple2< HashWrapper< long[] >, V >, long[], Tuple2< Interval, V > >
	{

		private final Broadcast< CellGrid > watershedsGrid;

		private final int[] context;

		public ToInterval( final JavaSparkContext sc, final CellGrid watershedsGrid, final int[] context )
		{
			super();
			this.watershedsGrid = sc.broadcast( watershedsGrid );
			this.context = context;
		}

		@Override
		public Tuple2< long[], Tuple2< Interval, V > > call( final Tuple2< HashWrapper< long[] >, V > t ) throws Exception
		{
			final CellGrid grid = this.watershedsGrid.getValue();
			final long[] location = t._1.getData();
			final long[] min = location.clone();
			for ( int d = 0; d < min.length; ++d )
				min[ d ] = Math.max( min[ d ] - context[ d ], 0 );
			final long[] max = IntStream.range( 0, min.length ).mapToLong( d -> Math.min( location[ d ] + grid.cellDimension( d ) + context[ d ], grid.imgDimension( d ) ) - 1 ).toArray();
//			System.out.println( "SETTING MIN AND MAX " + Arrays.toString( min ) + " " + Arrays.toString( max ) );
			final Interval interval = new FinalInterval( min, max );
			return new Tuple2<>( t._1().getData(), new Tuple2<>( interval, t._2() ) );
		}

	}

	public static class WriteN5< T extends NativeType< T > > implements Function< RandomAccessibleInterval< T >, Boolean >
	{

		private final String group;

		private final String n5Target;

		private final int[] cellSize;

		public WriteN5( final JavaSparkContext sc, final String group, final String n5Target, final int[] cellSize )
		{
			super();
			this.group = group;
			this.n5Target = n5Target;
			this.cellSize = cellSize;
		}

		@Override
		public Boolean call( final RandomAccessibleInterval< T > rai ) throws Exception
		{
			final N5FSWriter writer = new N5FSWriter( this.group );
			final long[] offset = IntStream.range( 0, 3 ).mapToLong( d -> rai.min( d ) / cellSize[ d ] ).toArray();
//			LOG.debug( "Saving block with offset {}", Arrays.toString( offset ) );
			N5Utils.saveBlock( rai, writer, n5Target, offset );
//			LOG.debug( "Saved block with offset {}", Arrays.toString( offset ) );
			return true;
		}

	}

	public static class Collapse< T > implements Function< RandomAccessible< T >, RandomAccessible< ? extends Composite< T > > >
	{

		@Override
		public RandomAccessible< ? extends Composite< T > > call( final RandomAccessible< T > ra ) throws Exception
		{
			return Views.collapse( ra );
		}

	}

	public static class ThresholdPredicate implements Predicate< FloatType >
	{

		private final double threshold;

		public ThresholdPredicate( final double threshold )
		{
			super();
			this.threshold = threshold;
		}

		@Override
		public boolean test( final FloatType ft )
		{
			return ft.getRealDouble() > threshold;
		}

	}

	public static class Distance implements ToDoubleBiFunction< FloatType, FloatType >
	{

		private final double minVal;

		public Distance( final double minVal )
		{
			super();
			this.minVal = minVal;
		}

		@Override
		public double applyAsDouble( final FloatType comparison, final FloatType reference )
		{
			return comparison.getRealDouble() > minVal ? -comparison.getRealDouble() : Double.POSITIVE_INFINITY;
		}

	}

//	final Predicate< FloatType > thresholdPredicate = c -> c.getRealDouble() > threshold;
//
//		final ToDoubleBiFunction< FloatType, FloatType > dist = ( c, r ) -> c.getRealDouble() > minVal ? -c.getRealDouble() : Double.POSITIVE_INFINITY;

}
