package abra;

import java.io.File;

import net.sf.samtools.MyReader;
import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMFileHeader.SortOrder;
import net.sf.samtools.SAMFileWriter;
import net.sf.samtools.SAMFileWriterFactory;
import net.sf.samtools.SAMRecord;

public class BetaPairValidatingRealignmentWriter implements RealignmentWriter {

	private SAMFileWriter writer;
	private ReAligner realigner;
	private IndelShifter indelShifter = new IndelShifter();
	
	private int realignCount = 0;
	
	private static final int INSERT_THRESHOLD = 5000;
	
	private int maxInsertLength;
	private int minInsertLength;
	
	private String candidatesSam;
	private SAMFileWriter candidatesSamWriter;
	private SamStringReader samStringReader;
	
	public BetaPairValidatingRealignmentWriter(ReAligner realigner, SAMFileWriter writer, String tempDir) {
		this.writer = writer;
		this.realigner = realigner;
		
		SAMFileHeader header = writer.getFileHeader().clone();
		header.setSortOrder(SortOrder.queryname);
		
		samStringReader = new SamStringReader(header);
		
		candidatesSam = tempDir + "/candidates.bam";
		
		candidatesSamWriter = new SAMFileWriterFactory().makeSAMOrBAMWriter(
				header, false, new File(candidatesSam));
		
		this.minInsertLength = realigner.getMinInsertLength();
		this.maxInsertLength = realigner.getMaxInsertLength();
	}
	
	long count = 1;
	
	int r1 = 0;
	int r2 = 0;
	int r3 = 0;
	int r4 = 0;
	int r5 = 0;
	int numCandidates = 0;

	private boolean isValidInsertLength(int insertLen) {
		return Math.abs(insertLen) >= minInsertLength && insertLen <= maxInsertLength;
	}
	
	private boolean isValidOrientation(SAMRecord read1, SAMRecord read2) {
		SAMRecord first;
		SAMRecord second;
		
		if (read1.getAlignmentStart() < read2.getAlignmentStart()) {
			first = read1;
			second = read2;
		} else {
			first = read2;
			second = read1;
		}
		
		return !first.getReadNegativeStrandFlag() && second.getReadNegativeStrandFlag();
	}
	
	public void addAlignment(SAMRecord updatedRead, SAMRecord origRead) {
		
		
		if (updatedRead == null) {
			// Just output the original read
			r1++;
			output(new Reads(updatedRead, origRead));
		} else if (updatedRead.getAttribute("YO") == null) {
			// Updated read has not moved, just output it
			r5++;
			output(new Reads(updatedRead, origRead));
		} else if (!origRead.getProperPairFlag()) {
			// Original read not part of "proper pair", output updated read
			r2++;
			output(new Reads(updatedRead, origRead));
		} else {
			// Output candidate to temp bam for comparison with mate
			r3++;
			writeToTempFile(candidatesSamWriter, updatedRead, origRead);
			numCandidates += 1;
		}
		
		if ((count++ % 100000) == 0) {
			System.out.println(r1 + "," + r2 + "," + r3 + "," + r4 + "," + r5);
			System.out.println("Num candidates: " + numCandidates);
		}
	}
	
	private void writeToTempFile(SAMFileWriter writer, SAMRecord updatedRead, SAMRecord origRead) {
		updatedRead.setAttribute("YG", origRead.getSAMString());
		writer.addAlignment(updatedRead);
	}
	
	private void outputPair(Reads first, Reads second) {
		checkPairValidity(first, second);
		output(first);
		output(second);
	}
	
	private int getInsertLength(int read1Start, int read1End, int read2Start, int read2End) {
		int start = Math.min(read1Start, read2Start);
		int end = Math.max(read1End, read2End);
		int len = end - start;
		
		return len;
	}
	
	private int getInsertLength(SAMRecord read1, SAMRecord read2) {
		return getInsertLength(read1.getAlignmentStart(), read1.getAlignmentEnd(),
				read2.getAlignmentStart(), read2.getAlignmentEnd());
	}
	
	private boolean isPairValid(SAMRecord read1, SAMRecord read2) {
		boolean isValid = false;
		
		if ((read1 != null) && (read2 != null)) {
			if (isSameChromosome(read1, read2)) {
				int len = getInsertLength(read1, read2);
				
				isValid = (isValidInsertLength(len)) && (isValidOrientation(read1, read2));
			}
		}
		
		return isValid;
	}
	
	private void checkPairValidity(Reads first, Reads second) {
		
		boolean isDone = false;
		
		if (isPairValid(first.getUpdatedRead(), second.getUpdatedRead())) {
			isDone = true;
		}
				
		if (!isDone) {
			if (isPairValid(first.getUpdatedRead(), second.getOrigRead())) {
				second.clearUpdatedRead();
				isDone = true;				
			}			
		}
		
		if (!isDone) {
			if (isPairValid(first.getOrigRead(), second.getUpdatedRead())) {
				first.clearUpdatedRead();
				isDone = true;
			}
		}
		
		if (!isDone) {
			first.clearUpdatedRead();
			second.clearUpdatedRead();
		}
	}
	
	private boolean isSameChromosome(SAMRecord read1, SAMRecord read2) {
		return read1.getReferenceName().equals(read2.getReferenceName());
	}
	
	int updatedCount = 0;
	int origCount = 0;
	
	private void output(Reads reads) {
		if (reads.getUpdatedRead() != null) {
			if (reads.getUpdatedRead().getAttribute("YO") != null) {
				realignCount += 1;
			}
			addAlignment(reads.getUpdatedRead());
			updatedCount += 1;
		} else {
			SAMRecord orig = reads.getOrigRead();
			addAlignment(orig);
			origCount += 1;
		}
	}
	
	private void addAlignment(SAMRecord read) {
		writer.addAlignment(indelShifter.shiftIndelsLeft(read, realigner.getC2r()));
	}
	
	private void processCandidates() {
		System.out.println("Processing candidates");
		SimpleSamReadPairReader reader = new SimpleSamReadPairReader(candidatesSam);
		
		for (ReadPair pair : reader) {
			SAMRecord updatedRead1 = pair.getRead1();
			SAMRecord updatedRead2 = pair.getRead2();
			SAMRecord origRead1 = getOriginalRead(updatedRead1);
			SAMRecord origRead2 = getOriginalRead(updatedRead2);
			
			if ((updatedRead1 !=  null) && (updatedRead2 != null)) {
				Reads reads1 = new Reads(updatedRead1, origRead1);
				Reads reads2 = new Reads(updatedRead2, origRead2);
				
				outputPair(reads1, reads2);
			} else if (updatedRead1 != null) {
				Reads reads1 = new Reads(updatedRead1, origRead1);
				checkOrigAndOutput(reads1);
			} else if (updatedRead2 != null) {
				Reads reads2 = new Reads(updatedRead2, origRead2);
				checkOrigAndOutput(reads2);
			}
		}
		System.out.println("Done processing candidates");
	}
	
	private SAMRecord getOriginalRead(SAMRecord read) {
		SAMRecord orig = null;
		
		if (read != null) {
			String origStr = (String) read.getAttribute("YG");
			orig = samStringReader.getRead(origStr);
			read.setAttribute("YG", null);
		}
		
		return orig;
	}
	
	public int flush() {
		System.out.println("Flushing");
		candidatesSamWriter.close();
		processCandidates();
		
		System.out.println("updatedCount: " + updatedCount);
		System.out.println("origCount: " + origCount);
		return realignCount;
	}
	
	private void checkOrigAndOutput(Reads reads) {
		if (!reads.getUpdatedRead().getReferenceName().equals(reads.getOrigRead().getReferenceName())) {
			reads.clearUpdatedRead();
		} else if (Math.abs(reads.getUpdatedRead().getAlignmentStart() - reads.getOrigRead().getAlignmentStart()) > 2 * INSERT_THRESHOLD) {
			reads.clearUpdatedRead();
		}
		output(reads);
	}
	
	class Reads {
		private SAMRecord updatedRead;
		private SAMRecord origRead;
		
		private String updatedReadStr;
		private String origReadStr;
		
		public Reads(SAMRecord updatedRead, SAMRecord origRead) {
			this.updatedRead = updatedRead;
			this.origRead = origRead;
		}

		public SAMRecord getUpdatedRead() {
			if ( (updatedReadStr != null) && (updatedRead == null) ) {
				updatedRead = MyReader.getRead(updatedReadStr, realigner.getHeader());
			}
			
			return updatedRead;
		}

		public SAMRecord getOrigRead() {
			if (origRead == null) {
				origRead = MyReader.getRead(origReadStr, realigner.getHeader());
			}
			
			return origRead;
		}
		
		public void clearUpdatedRead() {
			this.updatedRead = null;
			this.updatedReadStr = null;
		}
		
		public void stringify() {
			if (updatedRead != null) {
				this.updatedReadStr = updatedRead.getSAMString();
			}
			this.origReadStr = origRead.getSAMString();
			
			this.updatedRead = null;
			this.origRead = null;
		}
	}
}