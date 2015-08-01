package core.access.spark.join.algo;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputSplit;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import core.access.iterator.ReusablePartitionIterator;
import core.access.spark.SparkInputFormat.SparkFileSplit;
import core.access.spark.join.HPJoinInput;
import core.index.MDIndex;
import core.utils.Range;

/**
 * 
 * An approximate algorithm for combining buckets in a hierarchical manner.
 * 
 * This algorithm extends the formulation in:
 * 		"Fine-grained Partitioning for Aggressive Data Skipping", SIGMOD 2014.
 * ABove paper in turn uses the Ward's method in:
 *		"Hierarchical grouping to optimize an objective function", JASA 1963.
 *
 * We extend the above algorithms by:
 * - applying grouping to buckets (and not tuples as in "Data Skipping..")
 * - considering replication of buckets
 * - more elaborate set of stopping conditions
 * 
 * @author alekh
 *
 */
public class HyperJoinTuned extends JoinAlgo {

	private HPJoinInput joinInput1;
	private HPJoinInput joinInput2;	
	
	private int minSplits;
	private double maxPartitionSize;
	private double maxHashTableSize;
	
	public HyperJoinTuned(HPJoinInput joinInput1, HPJoinInput joinInput2, int minSplits) {
		this.joinInput1 = joinInput1;
		this.joinInput2 = joinInput2;
		this.minSplits = minSplits;
	}
	
	
	/**
	 * Return the ranges units (called Rangelets here).
	 * The return set contains disjoint and complete set of ranges.
	 * Each range in the set indicates the smallest unit of data that is shuffled/replicated.
	 * 
	 * The rangelets could be obtained by:
	 * 1. simply dividing the overall join key range
	 * 2. using join key distributions
	 * 3. using samples
	 * 
	 * @param overallRange
	 * @return
	 */
	protected List<Range> getRangelets(Range overallRange){
		return null;	// TODO
	}
	
	
	/**
	 * Create the list of virtual buckets based on the range units (the rangelets).
	 * Each virtual bucket intersects with only one of the range units.
	 * The set of virtual buckets is complete over the set of input buckets.
	 * 
	 * @param buckets
	 * @param rangelets
	 * @return
	 */
	protected List<VBucket> getVirtualBuckets(List<MDIndex.BucketInfo> buckets, List<Range> rangelets){
		return null;	// TODO
	}
	
	
	/**
	 * Perform a single iteration of the algorithm. Two steps involved:
	 * 1. find the pair of partitions which result in maximum reduction in cost
	 * 2. combine the above pair (if such a pair is found)
	 * 
	 * @param partitionSet
	 * @return
	 */
	protected void iterate(PartitionSet partitionSet){
		// create a list of all combine-able partition pairs 
		List<PartitionPair> candidatePairs = Lists.newArrayList();
		for(Partition p1: partitionSet.getPartitions())
			for(Partition p2: partitionSet.getPartitions()){
				PartitionPair partitionPair = new PartitionPair(p1, p2);
				if(partitionPair.checkCombine(maxPartitionSize, maxHashTableSize))
					candidatePairs.add(partitionPair);
			}
		
		// sort the partition pairs based on how much they can reduce the cost
		Collections.sort(candidatePairs, new Comparator<PartitionPair>() {
			public int compare(PartitionPair o1, PartitionPair o2) {
				if(o1.reductionC() > o2.reductionC())
					return 1;
				else if(o1.reductionC() < o2.reductionC())
					return -1;
				else
					return 0;
			}
		});

		// combine the first pair
		if(candidatePairs.size() > 0){
			PartitionPair p = candidatePairs.get(0);
			partitionSet.add(p.combine());
			partitionSet.remove(p.first());
			partitionSet.remove(p.second());
		}		
	}
	
	/**
	 * The getSplit method implementation. (This method is invoked from the driver class)
	 */
	public List<InputSplit> getSplits() {
		
		// step 1: get the set of range units
		List<Range> rangelets = getRangelets(joinInput1.getFullRange());		
		
		// step 2: create virtual buckets
		List<VBucket> vbuckets = getVirtualBuckets(joinInput1.getBucketRanges(), rangelets);
		
		// step 3: initialize the partition set
		PartitionSet partitionSet = new PartitionSet(vbuckets, joinInput2);
		
		// step 4: the heuristic based combine step
		long initialC = partitionSet.C();
		do{
			iterate(partitionSet);
		} while(
				(initialC - partitionSet.C() > 0) &&			// (i) there is reduction in size
				(partitionSet.size() > minSplits)				// (iii) number of partitions greater than threshold 
			);
		
		// step 5: return the final partition set as input splits
		return partitionSet.getInputSplits();
		
	}
	

	
/*
 * 
 * The helper classes follow below.
 * 
 * 
 */

	
	/**
	 * The physical bucket instance.
	 * I guess this cane be replaced with one of the existing classes, e.g. MDIndex.BucketInfo ?
	 * 
	 * @author alekh
	 */
	public static class PBucket{
		private long size;
		private Path path;
		public PBucket(MDIndex.BucketInfo info){
			// TODO: extract bucket size and path from info
		}
		public long size(){
			return size;
		}
		public Path path(){
			return path;
		}
	}
	
	/**
	 * The virtual bucket instance. 
	 * 
	 * @author alekh
	 */
	public static class VBucket{
		private PBucket b;
		private Range r;
		public VBucket(PBucket b, Range r){
			this.b = b;
		}
		public PBucket b(){
			return b;
		}
		public Range range(){
			return r;
		}
	}
	
	/**
	 * The Partition class which holds a set of virtual buckets.
	 * 
	 * @author alekh
	 */
	public static class Partition{
		private Set<VBucket> vbuckets;
		private Set<PBucket> pbuckets;
		private Range range;
		private long sizeA, sizeB;
		
		public Partition(VBucket vbucket, HPJoinInput secondInput){
			vbuckets = Sets.newHashSet(vbucket);
			pbuckets = Sets.newHashSet(vbucket.b());
			range = vbucket.range().clone();	// TODO: make sure clone is implemented in range 
												// (we use clone because the range of this partition could be later extended)
			sizeA = vbucket.b().size();
			sizeB = lookupSizeB(range);
		}
		protected Partition clone(){
			return null;	// TODO: implement
		}
		private long lookupSizeB(Range r){
			// TODO: need to do index lookup from the second input
			return 0;
		}
		public Set<VBucket> v(){
			return vbuckets;
		}
		public Set<PBucket> b(){
			return pbuckets;
		}
		public Range r(){
			return range;
		}
		public long sA(){
			return sizeA;
		}
		public long sB(){
			return sizeB;
		}
		public long C(){
			return sA() + sB();
		}		
	}
	
	/**
	 * A pair of partitions which are candidate for combining
	 * 
	 * @author alekh
	 */
	public static class PartitionPair{
		private Partition p1, p2;
		private long combinedSizeA, combinedSizeB;
		public PartitionPair(Partition p1, Partition p2){
			this.p1 = p1;
			this.p2 = p2;
			combinedSizeA = p1.sA() + p2.sA();
			Range tmp = p1.r().clone();
			tmp.union(p2.r());
			combinedSizeB = p1.lookupSizeB(tmp);
		}
		public Partition first(){
			return p1;
		}
		public Partition second(){
			return p2;
		}
		/**
		 * Check whether combining this partition pair makes sense or not.
		 * 
		 * @param maxPartitionSize
		 * @param maxHashTableSize
		 * @return 
		 * 		- the reduction in size due to combine, if the combined size is less than the max threshold and the hash table size is less than the max hash table threshold.
		 * 		- minimum long value otherwise.
		 * 
		 */
		public boolean checkCombine(double maxPartitionSize, double maxHashTableSize){
			return 
					(p1!=p2) &&
					(combinedSizeA + combinedSizeB <= maxPartitionSize) &&		// max split size check
					(combinedSizeB <= maxHashTableSize);						// max hash table size check
		}
		public Partition combine(){
			Partition p3 = p1.clone();
			p3.v().addAll(p2.v());
			p3.b().addAll(p2.b());
			p3.r().union(p2.r());
			p3.sizeA += p2.sizeA;
			p3.sizeB = p3.lookupSizeB(p3.r());
			return p3;
		}
		public long combinedSA(){
			return combinedSizeA;
		}
		public long combinedSB(){
			return combinedSizeB;
		}
		public long combinedC(){
			return combinedSA() + combinedSB();
		}
		public long reductionC(){
			return p1.C() + p2.C() - combinedC();
		}
	}
	
	/**
	 * An instance of a set of partitions.
	 * 
	 * @author alekh
	 */
	public static class PartitionSet {
		private Set<Partition> partitions;		
		public PartitionSet(List<VBucket> vbuckets, HPJoinInput secondInput){
			partitions = Sets.newHashSet();
			for(VBucket vbucket: vbuckets)
				partitions.add(new Partition(vbucket, secondInput));
		}
		public void add(Partition g){
			partitions.add(g);
		}
		public void remove(Partition g){
			partitions.remove(g);
		}
		public int size(){
			return partitions.size();
		}
		public long C(){
			long c = 0;
			for(Partition p: partitions)
				c += p.C();
			return c;
		}
		public Set<Partition> getPartitions(){
			return partitions;
		}
		public List<InputSplit> getInputSplits(){
			List<InputSplit> finalSplits = Lists.newArrayList();
			for(Partition partition: partitions){
				Path[] paths =new Path[partition.b().size()];
				long[] lengths =new long[partition.b().size()];
				int i=0;
				for(PBucket pbucket: partition.b()){
					paths[i] = pbucket.path();
					lengths[i] = pbucket.size();
					i++;
				}
				finalSplits.add(
						new SparkFileSplit(paths, lengths, new ReusablePartitionIterator())
					);
			}			
			return finalSplits;
		}
	}
}
