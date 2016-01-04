//BSD 2-Clause (c) 2014: A.Doherty (Oxford), D.Jackson, N.Hammerla (Newcastle)
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.time.temporal.ChronoField;
import java.text.SimpleDateFormat;
import java.text.DecimalFormat;

/**
 * Calculates epoch summaries from an AX3 .CWA file.
 * Class/application can be called from the command line as follows:
 * java AxivityAx3Epochs inputFile.CWA 
 */
public class AxivityAx3Epochs
{
	
	// preciseTime: false emulates original behaviour, true uses the block fractional time and interpolates the timestamps between blocks.
	private static final boolean USE_PRECISE_TIME = true;
    private static DecimalFormat DF6 = new DecimalFormat("0.000000");
    private static DecimalFormat DF2 = new DecimalFormat("0.00");
    private static LocalDateTime SESSION_START = null;
    private static long START_OFFSET_NANOS = 0; 

    /**
     * Parse command line args, then call method to identify & write epochs.
     */
    public static void main(String[] args) {
        //variables to store default parameter options
        String accFile = "";
        String[] functionParameters = new String[0];
        String outputFile = "";
        Boolean verbose = true;
        int epochPeriod = 5;
        DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        double lowPassCut = 20;
        double highPassCut = 0.2;
        int sampleRate = 100;
        //create Filters necessary for later data processing
        LowpassFilter filter = new LowpassFilter(lowPassCut, sampleRate);
        //BandpassFilter filter = new BandpassFilter(highPassCut, lowPassCut, sampleRate);
        Boolean startEpochWholeMinute = false;
        Boolean startEpochWholeSecond = false;
        Boolean getStationaryBouts = false;
        double stationaryStd = 0.013;
        double[] swIntercept = new double[]{0.0, 0.0, 0.0};
        double[] swSlope = new double[]{1.0, 1.0, 1.0};
        double[] tempCoef = new double[]{0.0, 0.0, 0.0};
        double meanTemp = 0.0;
        int range = 8;
        DF6.setRoundingMode(RoundingMode.CEILING);
        DF2.setRoundingMode(RoundingMode.CEILING);
        if (args.length < 1) {
            String invalidInputMsg = "Invalid input, ";
            invalidInputMsg += "please enter at least 1 parameter, e.g.\n";
            invalidInputMsg += "java AxivityAx3Epochs inputFile.CWA";
            System.out.println(invalidInputMsg);
            System.exit(0);
        } else if (args.length == 1) {
            //singe parameter needs to be accFile
            accFile = args[0]; 
            outputFile = accFile.split("\\.")[0] + "Epoch.csv";
        } else {
            //load accFile, and also copy functionParameters (args[1:])
            accFile = args[0];
            outputFile = accFile.split("\\.")[0] + "Epoch.csv";
            functionParameters = Arrays.copyOfRange(args, 1, args.length);

            //update default values by looping through available user parameters
            for (String individualParam : functionParameters) {
                //individual_Parameters will look like "epoch_period:60"
                String funcName = individualParam.split(":")[0];
                String funcParam = individualParam.split(":")[1];
                if (funcName.equals("outputFile")) {
                    outputFile = funcParam;
                } else if (funcName.equals("verbose")) {
                    verbose = Boolean.parseBoolean(funcParam.toLowerCase());
                } else if (funcName.equals("epochPeriod")) {
                    epochPeriod = Integer.parseInt(funcParam);
                } else if (funcName.equals("timeFormat")) {
                    timeFormat = DateTimeFormatter.ofPattern(funcParam);
                } else if (funcName.equals("filter")) {
                    if (!Boolean.parseBoolean(funcParam.toLowerCase())) {
                        filter = null;
                    }
                } else if (funcName.equals("startEpochWholeMinute")) {
                    startEpochWholeMinute = Boolean.parseBoolean(
                            funcParam.toLowerCase());
                } else if (funcName.equals("startEpochWholeSecond")) {
                    startEpochWholeSecond = Boolean.parseBoolean(
                            funcParam.toLowerCase());
                } else if (funcName.equals("getStationaryBouts")) {
                    getStationaryBouts = Boolean.parseBoolean(
                            funcParam.toLowerCase());
                    epochPeriod = 10;
                } else if (funcName.equals("stationaryStd")) {
                    stationaryStd = Double.parseDouble(funcParam);
                } else if (funcName.equals("xIntercept")) {
                    swIntercept[0] = Double.parseDouble(funcParam);
                } else if (funcName.equals("yIntercept")) {
                    swIntercept[1] = Double.parseDouble(funcParam);
                } else if (funcName.equals("zIntercept")) {
                    swIntercept[2] = Double.parseDouble(funcParam);
                } else if (funcName.equals("xSlope")) {
                    swSlope[0] = Double.parseDouble(funcParam);
                } else if (funcName.equals("ySlope")) {
                    swSlope[1] = Double.parseDouble(funcParam);
                } else if (funcName.equals("zSlope")) {
                    swSlope[2] = Double.parseDouble(funcParam);
                } else if (funcName.equals("xTemp")) {
                    tempCoef[0] = Double.parseDouble(funcParam);
                } else if (funcName.equals("yTemp")) {
                    tempCoef[1] = Double.parseDouble(funcParam);
                } else if (funcName.equals("zTemp")) {
                    tempCoef[2] = Double.parseDouble(funcParam);
                } else if (funcName.equals("meanTemp")) {
                    meanTemp = Double.parseDouble(funcParam);
                } else if (funcName.equals("range")) {
                    range = Integer.parseInt(funcParam);
                }
            }
        }    

        //process file if input parameters are all ok
        writeCwaEpochs(accFile, outputFile, verbose, epochPeriod, timeFormat,
                startEpochWholeMinute, startEpochWholeSecond, range, swIntercept,
                swSlope, tempCoef, meanTemp, getStationaryBouts, stationaryStd,
                filter);   
    }

    /**
     * Read CWA file blocks, then call method to write epochs from raw data.
     * Epochs will be written to path "outputFile".
     */
    private static void writeCwaEpochs(
            String accFile,
            String outputFile,
            Boolean verbose,
            int epochPeriod,
            DateTimeFormatter timeFormat,
            Boolean startEpochWholeMinute,
            Boolean startEpochWholeSecond,
            int range,
            double[] swIntercept,
            double[] swSlope,
            double[] tempCoef,
            double meanTemp,
            Boolean getStationaryBouts,
            double staticStd,
            LowpassFilter filter) {
        //file read/write objects
        FileChannel rawAccReader = null;
        BufferedWriter epochFileWriter = null;
        int bufSize = 512;
        ByteBuffer buf = ByteBuffer.allocate(bufSize);      
        try {
            rawAccReader = new FileInputStream(accFile).getChannel();
            epochFileWriter = new BufferedWriter(new FileWriter(outputFile));
            //data block support variables
            String header = "";        
            //epoch creation support variables
            LocalDateTime epochStartTime = null;
            List<Long> timeVals = new ArrayList<Long>();
            List<Double> xVals = new ArrayList<Double>();
            List<Double> yVals = new ArrayList<Double>();
            List<Double> zVals = new ArrayList<Double>();
            int[] errCounter = new int[]{0}; //store val if updated in other method
            int[] clipsCounter = new int[]{0, 0}; //before, after (calibration)
			// Inter-block timstamp tracking
			LocalDateTime[] lastBlockTime = { null };
			int[] lastBlockTimeIndex = { 0 };
			
            String epochSummary = "";
            String epochHeader = "Time,enmoTrunc,";
            if(getStationaryBouts){
                epochHeader += "xMean,yMean,zMean,";
            }
            epochHeader += "xRange,yRange,zRange,xStd,yStd,zStd,temp,samples,";
            epochHeader += "dataErrors,clipsBeforeCalibr,clipsAfterCalibr,";
            epochHeader += "rawSamples";

            //now read every page in CWA file
            int pageCount = 0;
            long memSizePages = rawAccReader.size()/bufSize;
            while(rawAccReader.read(buf) != -1) {
                buf.flip();
                buf.order(ByteOrder.LITTLE_ENDIAN);
                header = (char)buf.get() + "";
                header += (char)buf.get() + "";
                if(header.equals("MD")) {
                    //Read first page (& data-block) to get time, temp,
                    //measureFreq & start-epoch values
                    try{
                        SESSION_START = headerLoggingStartTime(buf);
                        System.out.println("Session start:" + SESSION_START);
                    } catch (Exception e){
                        System.err.println("No preset start time");
                    }
                    writeLine(epochFileWriter, epochHeader);
                } else if(header.equals("AX")) {
                    //read each individual page block, and process epochs...
                    try{
                        epochStartTime = processDataBlockIdentifyEpochs(buf,
                                epochFileWriter, timeFormat, epochStartTime,
                                epochPeriod, timeVals, xVals, yVals, zVals,
                                range, errCounter, clipsCounter, swIntercept,
                                swSlope, tempCoef, meanTemp, getStationaryBouts,
                                staticStd, filter, USE_PRECISE_TIME,
                                lastBlockTime, lastBlockTimeIndex);
                    } catch(Exception excep){
                        String errMsg = "block error at ";
                        errMsg += epochStartTime.toString();
                        errMsg += ": " + excep.toString();
                        excep.printStackTrace(System.err);
                        System.err.println(errMsg);
                    }
                }
                buf.clear();
                //option to provide status update to user...
                pageCount++;
                if(verbose && pageCount % 10000 == 0)
                    System.out.print((pageCount*100/memSizePages) + "%\b\b\b");
            }   
            rawAccReader.close();
            epochFileWriter.close();
        } catch (Exception excep) {
            String errorMessage = "error reading/writing file " + outputFile;
            errorMessage += ": " + excep.toString();
            excep.printStackTrace(System.err);
            System.err.println(errorMessage);
            System.exit(0);
        }
    }

    /**
     * Read data block HEX values, store each raw reading, then continually test
     * if an epoch of data has been collected or not. Finally, write each epoch
     * to <epochFileWriter>. Method also updates and returns <epochStartTime>.
     * CWA format is described at:
     * https://code.google.com/p/openmovement/source/browse/downloads/AX3/AX3-CWA-Format.txt
     */
    private static LocalDateTime processDataBlockIdentifyEpochs(
            ByteBuffer buf,
            BufferedWriter epochWriter,
            DateTimeFormatter timeFormat,
            LocalDateTime epochStartTime,
            int epochPeriod,
            List<Long> timeVals,
            List<Double> xVals,
            List<Double> yVals,
            List<Double> zVals,
            int range,
            int[] errCounter,
            int[] clipsCounter,
            double[] swIntercept,
            double[] swSlope,
            double[] tempCoef,
            double meanTemp,
            Boolean getStationaryBouts,
            double staticStd,
            LowpassFilter filter,
			boolean preciseTime,
			LocalDateTime[] lastBlockTime,
			int[] lastBlockTimeIndex
			) {
        //read block header items
        long blockTimestamp = getUnsignedInt(buf,14);// buf.getInt(14);
        int light = getUnsignedShort(buf,18);// buf.getShort(18);      
        double temperature = (getUnsignedShort(buf,20)*150.0 - 20500) / 1000;
        short rateCode = (short)(buf.get(24) & 0xff);
        short numAxesBPS = (short)(buf.get(25) & 0xff);
        int sampleCount = getUnsignedShort(buf, 28);// buf.getShort(28);
        short timestampOffset = 0;
        double sampleFreq = 0;
		int fractional = 0;	// 1/65536th of a second fractions

		//check not a very old file as pos 26=freq rather than timestamp offset
		if (rateCode != 0) {
			timestampOffset = buf.getShort(26); //ok to use timestamp offset
			//if fractional offset, then timestamp offset was artificially
            //modified for backwards-compatibility, undo this...
	        int oldDeviceId = getUnsignedShort(buf, 4);
			if ((oldDeviceId & 0x8000) != 0) {
				sampleFreq = 3200.0 / (1 << (15 - (rateCode & 15)));
				if (preciseTime) {
					// Need to undo backwards-compatible shim: Take into account
                    // how many whole samples the fractional part of timestamp 
                    // accounts for:  
					// relativeOffset = fifoLength - (short)(((unsigned long)timeFractional * AccelFrequency()) >> 16);
					//                         nearest whole sample
					//          whole-sec       | /fifo-pos@time
					//           |              |/
					// [0][1][2][3][4][5][6][7][8][9]
                    // use 15-bits as 16-bit fractional time
					fractional = ((oldDeviceId & 0x7fff) << 1);
                    //frequency is truncated to an integer in firmware
					timestampOffset += ((fractional * (int)sampleFreq) >> 16);
				}
			}
		} else {
			sampleFreq = buf.getShort(26); //very old format, where pos26 = freq
		}
        //calculate num bytes per sample...
        byte bytesPerSample = 4;
        int NUM_AXES_PER_SAMPLE = 3;
        if ((numAxesBPS & 0x0f) == 2) {
            bytesPerSample = 6; // 3*16-bit
        } else if ((numAxesBPS & 0x0f) == 0) {
            bytesPerSample = 4; // 3*10-bit + 2
        }
        // Limit values
		int maxSamples = 480 / bytesPerSample;	// 80 or 120 samples/block
		if (sampleCount > maxSamples) {
			sampleCount = maxSamples;
		}
        if (sampleFreq <= 0) {
            sampleFreq = 1;
        }
		
        // determine the time for the indexed sample within the block
        LocalDateTime blockTime = getCwaTimestamp((int)blockTimestamp, fractional);        
        // first & last sample time (actually, last = first sample in next block)
		LocalDateTime firstSampleTime, lastSampleTime;
		// if we don't have an interval between our times (or interval too large)
		long spanToSample = 0;
		if (lastBlockTime[0] != null){
            spanToSample = Duration.between(lastBlockTime[0], blockTime).toNanos();
        }
		if (!preciseTime || lastBlockTime[0] == null ||
                timestampOffset <= lastBlockTimeIndex[0] || spanToSample <= 0 ||
                spanToSample > 1000000000.0 * 2 * maxSamples / sampleFreq) {
			float offsetStart = (float)-timestampOffset / (float)sampleFreq;
			firstSampleTime = blockTime.plusNanos(secs2Nanos(offsetStart));
			lastSampleTime = firstSampleTime.plusNanos(secs2Nanos(sampleCount / sampleFreq));
			//System.out.println("Unable to use last time (@" + lastBlockTimeIndex[0] + "=" + lastBlockTime[0] + "), estimating from rate (offset " + timestampOffset + "): " + firstSampleTime + ", " + lastSampleTime + "");
		} else {
            double gap = (double)spanToSample / (-lastBlockTimeIndex[0] + timestampOffset);
            firstSampleTime = lastBlockTime[0].plusNanos((long)(-lastBlockTimeIndex[0] * gap));
            lastSampleTime = lastBlockTime[0].plusNanos((long)((-lastBlockTimeIndex[0] + sampleCount) * gap));
            //System.out.println("From last (@" + lastBlockTimeIndex[0] + "=" + lastBlockTime[0] + ") and new time (@" + timestampOffset + "=" + blockTime + "): " + firstSampleTime + ", " + lastSampleTime + "");
		}

		// Last block time
		lastBlockTime[0] = blockTime;
        //Advance last block time index for next block
		lastBlockTimeIndex[0] = timestampOffset - sampleCount;
		// Overall span
		long spanNanos = Duration.between(firstSampleTime, lastSampleTime).toNanos();
		//System.out.println("Block is " + spanNanos / 1000000000.0 + "s => samples period " + spanNanos / 1000000000.0 / sampleCount);
        //set target epoch start time of very first block
        if(epochStartTime==null) {
            epochStartTime = firstSampleTime;
            //if set, clamp whole session to intended logging start time
            if(SESSION_START!=null){
                START_OFFSET_NANOS = Duration.between(epochStartTime,
                        SESSION_START).toNanos();
                //check block time and session start time are within 10secs
                long clampLimitNanos = secs2Nanos(15);
                if( START_OFFSET_NANOS > clampLimitNanos ||
                        START_OFFSET_NANOS < -clampLimitNanos ){
                    START_OFFSET_NANOS = 0;
                    System.out.println("Can't clamp to logging start time");
                }
            }
        }

        //raw reading values
        long value = 0; // x/y/z vals
        short xRaw = 0;
        short yRaw = 0;
        short zRaw = 0;
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        double mcTemp = temperature-meanTemp; //mean centred temperature
        
        //loop through each line in data block & check if it is last in epoch
        //then write epoch summary to file
        //an epoch will have a start+end time, and be of fixed duration            
        int currentPeriod;
        Boolean isClipped = false;
        for (int i = 0; i<sampleCount; i++) {
			
			//Calculate each sample's time, not successively adding so that we
            //don't accumulate any errors
			if (preciseTime) {
				blockTime = firstSampleTime.plusNanos(
                        (long)(i * (double)spanNanos / sampleCount) );
			} else if (i == 0) {
				blockTime = firstSampleTime; //emulate original behaviour
			}
			
            if (bytesPerSample == 4) {
                try {
                    value = getUnsignedInt(buf, 30 +4*i);
                } catch (Exception excep) {
                    errCounter[0] += 1;
                    System.err.println("xyz reading err: " + excep.toString());
                    break; //rest of block/page could be corrupted
                }
                // Sign-extend 10-bit values, adjust for exponents
                xRaw = (short)((short)(0xffffffc0 & (value <<  6)) >> (6 - ((value >> 30) & 0x03)));
                yRaw = (short)((short)(0xffffffc0 & (value >>  4)) >> (6 - ((value >> 30) & 0x03)));
                zRaw = (short)((short)(0xffffffc0 & (value >>  14)) >> (6 - ((value >> 30) & 0x03)));
            } else if (bytesPerSample == 6) {
                try {
                    errCounter[0] += 1;
                    xRaw = buf.getShort(30 + 2 * NUM_AXES_PER_SAMPLE * i + 0);
                    yRaw = buf.getShort(30 + 2 * NUM_AXES_PER_SAMPLE * i + 2);
                    zRaw = buf.getShort(30 + 2 * NUM_AXES_PER_SAMPLE * i + 4);
                } catch (Exception excep) {
                    System.err.println("xyz reading err: " + excep.toString());
                    break; //rest of block/page could be corrupted
                }
            } else {
                xRaw = 0;
                yRaw = 0;
                zRaw = 0;
            }            
            x = xRaw / 256.0;
            y = yRaw / 256.0;
            z = zRaw / 256.0;
            //check if any clipping present, use ==range as it's clipped here
            if(x<=-range || x>=range || y<=-range || y>=range || z<=-range || z>=range){
                clipsCounter[0] += 1;
                isClipped = true;
            }

            //update values to software calibrated values
            x = swIntercept[0] + x*swSlope[0] + mcTemp*tempCoef[0];
            y = swIntercept[1] + y*swSlope[1] + mcTemp*tempCoef[1];
            z = swIntercept[2] + z*swSlope[2] + mcTemp*tempCoef[2];
            //check if any new clipping has happened
            //find crossing of range threshold so use < rather than ==
            if(x<-range || x>range || y<-range || y>range || z<-range || z>range){
                if (!isClipped)
                    clipsCounter[1] += 1;
                //drag post calibration clipped values back to range limit
                if (x<-range || (isClipped && x<0))
                    x = -range;
                else if (x>range || (isClipped && x>0))
                    x = range;
                if (y<-range || (isClipped && y<0))
                    y = -range;
                else if (y>range || (isClipped && y>0))
                    y = range;
                if (z<-range || (isClipped && z<0))
                    z = -range;
                else if (z>range || (isClipped && z>0))
                    z = range;
            }
            
            currentPeriod = (int)Duration.between(epochStartTime,blockTime).getSeconds();
            //check for an interrupt, i.e. where break in values > 2 * epochPeriod
            if (currentPeriod >= epochPeriod*2) {
                int epochDiff = currentPeriod/epochPeriod;
                epochStartTime = epochStartTime.plusSeconds(epochPeriod*epochDiff);
                //and update how far we are into the new epoch...
                currentPeriod = (int) ((blockTime.get(ChronoField.MILLI_OF_SECOND) -
                        epochStartTime.get(ChronoField.MILLI_OF_SECOND))/1000);
            }
            
            //check we have collected enough values to form an epoch
            if (currentPeriod >= epochPeriod){
                //resample values to epochSec * (intended) sampleRate
                long[] timeResampled = new long[epochPeriod * (int)sampleFreq];
                for(int c=0; c<timeResampled.length; c++){
                    timeResampled[c] = timeVals.get(0) + (10*c);
                }
                double[] xResampled = new double[timeResampled.length];
                double[] yResampled = new double[timeResampled.length];
                double[] zResampled = new double[timeResampled.length];
                Resample.interpLinear(timeVals, xVals, yVals, zVals,
                        timeResampled, xResampled, yResampled, zResampled);
                
                //epoch variables
                String epochSummary = "";
                double accPA = 0;
                double xMean = 0;
                double yMean = 0;
                double zMean = 0;
                double xRange = 0;
                double yRange = 0;
                double zRange = 0;
                double xStd = 0;
                double yStd = 0;
                double zStd = 0;     

                //calculate raw x/y/z summary values
                xMean = mean(xResampled);
                yMean = mean(yResampled);
                zMean = mean(zResampled);
                xRange = range(xResampled);
                yRange = range(yResampled);
                zRange = range(zResampled);
                xStd = std(xResampled, xMean);
                yStd = std(yResampled, yMean);
                zStd = std(zResampled, zMean);

                //see if values have been abnormally stuck this epoch
                double stuckVal = 1.5;
                if (xStd==0 && (xMean<-stuckVal || xMean>stuckVal))
                    errCounter[0] += 1;
                if (yStd==0 && (yMean<-stuckVal || yMean>stuckVal))
                    errCounter[0] += 1;
                if (zStd==0 && (zMean<-stuckVal || zMean>stuckVal))
                    errCounter[0] += 1;
               
                //calculate summary vector magnitude based metrics
                List<Double> paVals = new ArrayList<Double>();
                if(!getStationaryBouts) {
                    for(int c=0; c<xResampled.length; c++){
                        x = xResampled[c];
                        y = yResampled[c];
                        z = zResampled[c];
                        if(!Double.isNaN(x)) {
                            double vm = getVectorMagnitude(x,y,z);
                            paVals.add(vm-1);
                        }
                    }

                    //filter AvgVm-1 values
                    if (filter != null) {
                        filter.filter(paVals);
                    }

                    //run abs() or trunc() on summary variables after filtering
                    trunc(paVals); //abs(paVals)
                   
                    //calculate mean values for each outcome metric 
                    accPA = mean(paVals);
                }
                //write summary values to file
                epochSummary = epochStartTime.plusNanos(
                        START_OFFSET_NANOS).format(timeFormat);
                epochSummary += "," + DF6.format(accPA);
                if(getStationaryBouts){
                    epochSummary += "," + DF6.format(xMean);
                    epochSummary += "," + DF6.format(yMean);
                    epochSummary += "," + DF6.format(zMean);
                }
                epochSummary += "," + DF6.format(xRange);
                epochSummary += "," + DF6.format(yRange);
                epochSummary += "," + DF6.format(zRange);
                epochSummary += "," + DF6.format(xStd);
                epochSummary += "," + DF6.format(yStd);
                epochSummary += "," + DF6.format(zStd);
                epochSummary += "," + DF2.format(temperature);
                epochSummary += "," + xResampled.length + "," + errCounter[0];
                epochSummary += "," + clipsCounter[0] + "," + clipsCounter[1];
                epochSummary += "," + timeVals.size(); 
                if(!getStationaryBouts || 
                        (xStd<staticStd && yStd<staticStd && zStd<staticStd)) {
                    writeLine(epochWriter, epochSummary);        
                }
                       
                //reset target start time and reset arrays for next epoch
                epochStartTime = epochStartTime.plusSeconds(epochPeriod);
                timeVals.clear();
                xVals.clear();
                yVals.clear();
                zVals.clear();
                errCounter[0] = 0;
                clipsCounter[0] = 0;
                clipsCounter[1] = 0;
            }
            //store axes and vector magnitude values for every reading
            timeVals.add(Duration.between(epochStartTime, blockTime).toMillis());
            xVals.add(x);
            yVals.add(y);
            zVals.add(z);
            isClipped = false;
            //System.out.println(blockTime.format(timeFormat) + "," + x + "," + y + "," + z);
			if (!preciseTime) {
                // Moved this to recalculate at top (rather than potentially 
                // accumulate slight errors with repeated addition)
				blockTime = blockTime.plusNanos(secs2Nanos(1.0 / sampleFreq));
			}
        }
        return epochStartTime;
    }

	
    //Parse header HEX values, CWA format is described at:
    //https://code.google.com/p/openmovement/source/browse/downloads/AX3/AX3-CWA-Format.txt
    private static LocalDateTime headerLoggingStartTime(ByteBuffer buf) {
        //deviceId = getUnsignedShort(buf,5);
        //sessionId = getUnsignedInt(buf,7);
        long delayedLoggingStartTime = getUnsignedInt(buf,13);
        return getCwaTimestamp((int)delayedLoggingStartTime, 0);
    }
    
    private static LocalDateTime headerLoggingEndTime(ByteBuffer buf) {
        long delayedLoggingEndTime = getUnsignedInt(buf,17);
        return getCwaTimestamp((int)delayedLoggingEndTime, 0);
    }

    //credit for next 2 methods goes to:
    //http://stackoverflow.com/questions/9883472/is-it-possiable-to-have-an-unsigned-bytebuffer-in-java
    private static long getUnsignedInt(ByteBuffer bb, int position) {
        return ((long) bb.getInt(position) & 0xffffffffL);
    }

    private static int getUnsignedShort(ByteBuffer bb, int position) {
        return (bb.getShort(position) & 0xffff);
    }

    private static LocalDateTime getCwaTimestamp(
            int cwaTimestamp,
            int fractional) {
        LocalDateTime tStamp;
        int year = (int)((cwaTimestamp >> 26) & 0x3f) + 2000;
        int month = (int)((cwaTimestamp >> 22) & 0x0f);
        int day = (int)((cwaTimestamp >> 17) & 0x1f);
        int hours = (int)((cwaTimestamp >> 12) & 0x1f);
        int mins = (int)((cwaTimestamp >>  6) & 0x3f);
        int secs = (int)((cwaTimestamp      ) & 0x3f);
        tStamp = LocalDateTime.of(year, month, day, hours, mins, secs);
		// add 1/65536th fractions of a second
		tStamp = tStamp.plusNanos(secs2Nanos(fractional / 65536.0));
        return tStamp;
    }            
      
    private static double getVectorMagnitude(double x, double y, double z) {
        return Math.sqrt(x*x + y*y + z*z);
    }

    private static void abs(List<Double> vals) {
        for(int c=0; c<vals.size(); c++) {
            vals.set(c, Math.abs(vals.get(c)));
        }
    }
    
    private static void trunc(List<Double> vals) {
        double tmp;
        for(int c=0; c<vals.size(); c++) {
            tmp = vals.get(c);
            if(tmp < 0){
                tmp = 0;
            }
            vals.set(c, tmp);
        }
    }

    private static double sum(double[] vals) {
        if(vals.length==0) {
            return Double.NaN;
        }
        double sum = 0;
        for(int c=0; c<vals.length; c++) {
            if(!Double.isNaN(vals[c])) {
                sum += vals[c];
            }
        }
        return sum;
    }
    
    private static double mean(double[] vals) {
        if(vals.length==0) {
            return Double.NaN;
        }
        return sum(vals) / (double)vals.length;
    }
    
    private static double mean(List<Double> vals) {
        if(vals.size()==0) {
            return Double.NaN;
        }
        return sum(vals) / (double)vals.size();
    }
    
    private static double sum(List<Double> vals) {
        if(vals.size()==0) {
            return Double.NaN;
        }
        double sum = 0;
        for(int c=0; c<vals.size(); c++) {
            sum += vals.get(c);
        }
        return sum;
    }
    	
    private static double range(double[] vals) {
        if(vals.length==0) {
            return Double.NaN;
        }
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for(int c=0; c<vals.length; c++) {
            if (vals[c] < min) {
                min = vals[c];
            }
            if (vals[c] > max) {
                max = vals[c];
            }
        }
        return max - min;
    }    	

    private static double std(double[] vals, double mean) {
        if(vals.length==0) {
            return Double.NaN;
        }
        double var = 0; //variance
        double len = vals.length*1.0; //length
        for(int c=0; c<vals.length; c++) {
            if(!Double.isNaN(vals[c])) {
                var += ((vals[c] - mean) * (vals[c] - mean)) / len;
            }
        }
        return Math.sqrt(var);
    }

    private static void writeLine(BufferedWriter fileWriter, String line) {
        try {
            fileWriter.write(line + "\n");
        } catch (Exception excep) {
            System.err.println("line write error: " + excep.toString());
        }
    }

    private static long secs2Nanos(double num){
        return (long)(TimeUnit.SECONDS.toNanos(1)*num);
    }
      
}