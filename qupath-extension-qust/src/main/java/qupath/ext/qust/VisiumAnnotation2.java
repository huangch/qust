/*-
 * #%L
 * ST-AnD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * ST-AnD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with ST-AnD.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.qust;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.FileFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.opencsv.CSVReader;
import org.json.JSONObject;
import org.json.JSONArray;

import javafx.beans.property.StringProperty;
import javafx.geometry.Point2D;
import qupath.lib.common.GeneralTools;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathROIObject;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */



public class VisiumAnnotation2 extends AbstractDetectionPlugin<BufferedImage> {
	
	final private static Logger logger = LoggerFactory.getLogger(VisiumAnnotation2.class);
	final private StringProperty vsumAntnVsumFldrProp = PathPrefs.createPersistentPreference("vsumAntnVsumFldr", ""); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
	private ParameterList params;

	private final List<String> formatList = List.of("detection", "annotation");
//	private final List<String> rotationList = List.of("-270", "-180", "-90", "0", "90", "180", "270");
	
	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public VisiumAnnotation2() {
		params = new ParameterList()
			.addStringParameter("visiumDir", "Visium output directory", vsumAntnVsumFldrProp.get(), "Visium output directory")
			.addDoubleParameter("spotDiameter", "Spot diameter", 65, GeneralTools.micrometerSymbol(), "Spot diameter")
			.addChoiceParameter("format", "Output format", formatList.get(0), formatList, "Output format")
			.addBooleanParameter("tissueRegionsOnly", "Tissue regions only?", true, "Tissue regions only?")
			.addBooleanParameter("loadTranscriptData", "Load transcript data?", false, "Load transcript data?")
			.addBooleanParameter("cytAstTransf", "Transformation based on cytassist alignment?", false, "Transformation based on cytassist alignment?")
			.addBooleanParameter("siftAffineTransf", "Transformation based on SIFT alignment?", false, "Transformation based on SIFT alignment?")
			.addBooleanParameter("bsplineTransf", "Transformation based on bSpline alignment?", false, "Transformation based on bSpline alignment?")
			.addBooleanParameter("manualShift", "Manual Shift?", false, "Manual Shift?")
			;
	}
	
	/**
	 * Read the number of intervals of a transformation from a file.
	 *
	 * @param filename transformation file name
	 * @return number of intervals
	 */
	public int numberOfIntervalsOfTransformation(String filename)
	{
		try {
			final FileReader fr = new FileReader(filename);
			final BufferedReader br = new BufferedReader(fr);
			String line;

			// Read number of intervals
			line = br.readLine();
			int lineN=1;
			StringTokenizer st=new StringTokenizer(line,"=");
			if (st.countTokens()!=2) {
				fr.close();
				logger.error("Line "+lineN+"+: Cannot read number of intervals");
				return -1;
			}
			st.nextToken();
			int intervals=Integer.valueOf(st.nextToken()).intValue();

			fr.close();
			return intervals;
		} catch (FileNotFoundException e) {
			logger.error("File not found exception" + e);
			return -1;
		} catch (IOException e) {
			logger.error("IOException exception" + e);
			return -1;
		} catch (NumberFormatException e) {
			logger.error("Number format exception" + e);
			return -1;
		}
	}

	//------------------------------------------------------------------
	/**
	 * Load a transformation from a file.
	 *
	 * @param filename transformation file name
	 * @param cx x- B-spline coefficients
	 * @param cy y- B-spline coefficients
	 */
	public void loadTransformation(String filename,
			final double [][]cx, final double [][]cy)
	{
		try {
			final FileReader fr = new FileReader(filename);
			final BufferedReader br = new BufferedReader(fr);
			String line;

			// Read number of intervals
			line = br.readLine();
			int lineN = 1;
			StringTokenizer st = new StringTokenizer(line,"=");
			if (st.countTokens()!=2)
			{
				br.close();
				fr.close();
				logger.info("Line "+lineN+"+: Cannot read number of intervals");
				return;
			}
			st.nextToken();
			int intervals=Integer.valueOf(st.nextToken()).intValue();

			// Skip next 2 lines
			line = br.readLine();
			line = br.readLine();
			lineN+=2;

			// Read the cx coefficients
			for (int i= 0; i<intervals+3; i++)
			{
				line = br.readLine(); 
				lineN++;
				st=new StringTokenizer(line);
				if (st.countTokens()!=intervals+3)
				{
					br.close();
					fr.close();
					logger.info("Line "+lineN+": Cannot read enough coefficients");
					return;
				}
				for (int j=0; j<intervals+3; j++)
					cx[i][j]=Double.valueOf(st.nextToken()).doubleValue();
			}

			// Skip next 2 lines
			line = br.readLine();
			line = br.readLine();
			lineN+=2;

			// Read the cy coefficients
			for (int i= 0; i<intervals+3; i++)
			{
				line = br.readLine(); 
				lineN++;
				st = new StringTokenizer(line);
				if (st.countTokens()!=intervals+3)
				{
					br.close();
					fr.close();
					logger.info("Line "+lineN+": Cannot read enough coefficients");
					return;
				}
				for (int j=0; j<intervals+3; j++)
					cy[i][j]=Double.valueOf(st.nextToken()).doubleValue();
			}
			fr.close();
		} catch (FileNotFoundException e) {
			logger.error("File not found exception" + e);
			return;
		} catch (IOException e) {
			logger.error("IOException exception" + e);
			return;
		} catch (NumberFormatException e) {
			logger.error("Number format exception" + e);
			return;
		}
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {			
			final List<PathObject> pathObjects = new ArrayList<PathObject>();
			
			pathObjects.addAll(imageData.getHierarchy().getRootObject().getChildObjects());
			
			try {
				final InputStream is = Paths.get(vsumAntnVsumFldrProp.get(), "registration_params.json").toFile().exists()? 
						new FileInputStream(Paths.get(vsumAntnVsumFldrProp.get(), "registration_params.json").toString()):
						null;
				
				final String jsonTxt = is != null? IOUtils.toString(is, "UTF-8"): null;
				final JSONObject imgRegParamJsonObj = jsonTxt != null? new JSONObject(jsonTxt): null;   
				
				final double vsumAnnotImgRegParamManualScale = imgRegParamJsonObj == null? 1: imgRegParamJsonObj.getDouble("vsumAnnotImgRegParamManualScale");
				final int vsumAnnotImgRegParamSrcImgWidth = imgRegParamJsonObj == null? 1: (int)(0.5+vsumAnnotImgRegParamManualScale*imgRegParamJsonObj.getInt("vsumAnnotImgRegParamSrcImgWidth"));
				final int vsumAnnotImgRegParamSrcImgHeight = imgRegParamJsonObj == null? 1: (int)(0.5+vsumAnnotImgRegParamManualScale*imgRegParamJsonObj.getInt("vsumAnnotImgRegParamSrcImgHeight"));
				final boolean vsumAnnotImgRegParamFlipHori = imgRegParamJsonObj == null? false: imgRegParamJsonObj.getBoolean("vsumAnnotImgRegParamFlipHori");
				final boolean vsumAnnotImgRegParamFlipVert = imgRegParamJsonObj == null? false: imgRegParamJsonObj.getBoolean("vsumAnnotImgRegParamFlipVert");
				final String vsumAnnotImgRegParamRotation = imgRegParamJsonObj == null? null: imgRegParamJsonObj.getString("vsumAnnotImgRegParamRotation");
				final double[] vsumAnnotImgRegParamSiftMatrix = imgRegParamJsonObj == null? null: IntStream.range(0, 6).mapToDouble(i -> imgRegParamJsonObj.getJSONArray("vsumAnnotImgRegParamSiftMatrix").getDouble(i)).toArray();
				final double vsumAnnotImgRegParamSourceScale = imgRegParamJsonObj == null? 1: imgRegParamJsonObj.getDouble("vsumAnnotImgRegParamSourceScale");
				final double vsumAnnotImgRegParamTargetScale = imgRegParamJsonObj == null? 1: imgRegParamJsonObj.getDouble("vsumAnnotImgRegParamTargetScale");
				final int vsumAnnotImgRegParamShiftX = imgRegParamJsonObj == null? 0: imgRegParamJsonObj.getInt("vsumAnnotImgRegParamShiftX");
				final int vsumAnnotImgRegParamShiftY = imgRegParamJsonObj == null? 0: imgRegParamJsonObj.getInt("vsumAnnotImgRegParamShiftY");
				
					
				
				final File visiumFileFolder = new File(vsumAntnVsumFldrProp.get());
				final FileFilter spatialTarGzFileFilter = new WildcardFileFilter("*spatial.tar.gz");
				final File[] spatialTarGzFileList = visiumFileFolder.listFiles(spatialTarGzFileFilter);
				
				BufferedReader spatialBufferReader = null;

				if(spatialTarGzFileList.length == 1) {
					TarArchiveInputStream spatialTarGzFileStream = new TarArchiveInputStream(new GzipCompressorInputStream(new FileInputStream(spatialTarGzFileList[0])));
					TarArchiveEntry spatialTarGzFileStreamEntry = spatialTarGzFileStream.getNextTarEntry();	
					
					assert spatialTarGzFileStreamEntry != null: "Opening *spatial.tar.gz failed.";
					
					while (spatialTarGzFileStreamEntry != null) {
						spatialBufferReader = new BufferedReader(new InputStreamReader(spatialTarGzFileStream));
						if(spatialTarGzFileStreamEntry.getName().equals("spatial/tissue_positions_list.csv") || spatialTarGzFileStreamEntry.getName().equals("spatial/tissue_positions.csv")) break;
	
						spatialTarGzFileStreamEntry = spatialTarGzFileStream.getNextTarEntry(); // You forgot to iterate to the next file
					}
					
					assert spatialTarGzFileStreamEntry != null: "spatial/tissue_positions(_list).csv failed.";
				}
				else if(spatialTarGzFileList.length == 0) {
					final File spatialDir = new File(Paths.get(vsumAntnVsumFldrProp.get(), "spatial").toString());
					final FileFilter spatialCsvFileFilter = new WildcardFileFilter("tissue_positions*.csv");
					final File[] spatialCsvFileList =spatialDir.listFiles(spatialCsvFileFilter);
					
					for(File pos_fp: spatialCsvFileList) {
						if(pos_fp.exists() && !pos_fp.isDirectory()) {
							spatialBufferReader = new BufferedReader(new FileReader(pos_fp));
							break;
						}
						else {
							continue;
						}
					}
				}
				else {
					throw new Exception("Number of *spatial.tar.gz is wrong.");
				}
				
				assert spatialBufferReader != null: "Opening tissue_positions failed.";
				
				final CSVReader spatialReader = new CSVReader(spatialBufferReader);
				final HashMap<String, List<Integer>> spatialHMap = new HashMap<String, List<Integer>>();
			     
		        String[] spatialNextRecord;
		        List<Point2D> posList = new ArrayList<Point2D>();
		        
		        spatialReader.readNext();
		        
		        while ((spatialNextRecord = spatialReader.readNext()) != null) {
		        	List<Integer> list = new ArrayList<Integer>();
		        	list.add(Integer.parseInt(spatialNextRecord[1]));
		        	list.add(Integer.parseInt(spatialNextRecord[2]));
		        	list.add(Integer.parseInt(spatialNextRecord[3]));
		        	list.add(Integer.parseInt(spatialNextRecord[4]));
		        	list.add(Integer.parseInt(spatialNextRecord[5]));
		        	
		        	posList.add(new Point2D(Double.parseDouble(spatialNextRecord[4]), Double.parseDouble(spatialNextRecord[5])));
		        	
		        	spatialHMap.put(spatialNextRecord[0], list);
		        }
		        
		        spatialReader.close();

		        final FileFilter analysisTarGzFileFilter = new WildcardFileFilter("*analysis.tar.gz");
				final File[] analysisTarGzFileList = visiumFileFolder.listFiles(analysisTarGzFileFilter);
				
				BufferedReader analysisBufferReader = null;
				
				if(analysisTarGzFileList.length == 1) {
					final TarArchiveInputStream analysisTarGzFileStream = new TarArchiveInputStream(new GzipCompressorInputStream(new FileInputStream(analysisTarGzFileList[0])));
					TarArchiveEntry analysisTarGzFileStreamEntry = analysisTarGzFileStream.getNextTarEntry();	
					assert analysisTarGzFileStreamEntry != null: "Opening *spatial.tar.gz failed.";
					
					while (analysisTarGzFileStreamEntry != null) {
						analysisBufferReader = new BufferedReader(new InputStreamReader(analysisTarGzFileStream));
						if(analysisTarGzFileStreamEntry.getName().equals("analysis/clustering/graphclust/clusters.csv")) break;
	
						analysisTarGzFileStreamEntry = analysisTarGzFileStream.getNextTarEntry();
					}
					
					assert analysisTarGzFileStreamEntry != null: "analysis/clustering/graphclust/clusters.csv failed.";
				}
				else if(analysisTarGzFileList.length == 0) {
					final List<String> clustersCsvList = Arrays.asList(
							Paths.get(vsumAntnVsumFldrProp.get(), "analysis/clustering/graphclust/clusters.csv").toString(), 
							Paths.get(vsumAntnVsumFldrProp.get(), "analysis/clustering/gene_expression_graphclust/clusters.csv").toString());
					
					for(String csvFileName: clustersCsvList) {
						final File clustersCsvFile = new File(csvFileName);
						if(clustersCsvFile.exists() && !clustersCsvFile.isDirectory()) {
							analysisBufferReader = new BufferedReader(new FileReader(clustersCsvFile));
							break;
						}
						else {
							continue;
						}
					}
				}
				else {
					throw new Exception("Number of *analysis.tar.gz is wrong.");
				}
				
				final ImageServer<BufferedImage> server = imageData.getServer();
		        final double imagePixelSizeMicrons = server.getPixelCalibration().getAveragedPixelSizeMicrons();
		        
		        final HashMap<String, Integer> analysisHMap = new HashMap<String, Integer>();
		        int clsNum = 0;
		        
		        if(analysisBufferReader != null) {
		        	final CSVReader clusterReader = new CSVReader(analysisBufferReader);
	
			        String[] clusterNextRecord;

			        while ((clusterNextRecord = clusterReader.readNext()) != null) {
			            try {
			                final Integer cls = Integer.parseInt(clusterNextRecord[1]);
			                clsNum = cls > clsNum? cls: clsNum;
			                analysisHMap.put(clusterNextRecord[0], cls);
			            } catch (NumberFormatException nfe) {}
			        }
			        clusterReader.close();
		        }

		        double[][] cytassistAlignmentMatrix = null;
		        
		        if(params.getBooleanParameterValue("cytAstTransf")) {
		        	final File cytassistAlignmentFile = Paths.get(vsumAntnVsumFldrProp.get(), "cytassist_alignment.json").toFile();
			        final String cytassistAlignmentJsonStr = new String(Files.readAllBytes(Paths.get(cytassistAlignmentFile.toURI())));
		        	final JSONObject cytAstJsonObj = new JSONObject(cytassistAlignmentJsonStr);
		        	final JSONObject cytAssistInfoJsonObj = cytAstJsonObj.getJSONObject("cytAssistInfo");
		        	final JSONArray transformImagesJsonAry = cytAssistInfoJsonObj.getJSONArray("transformImages");
		        	
		        	cytassistAlignmentMatrix = new double[transformImagesJsonAry.length()][];
		        	
		        	for(int i = 0; i < transformImagesJsonAry.length(); i ++) {
		        		JSONArray innerArray = transformImagesJsonAry.getJSONArray(i);
		        		cytassistAlignmentMatrix[i] = new double[innerArray.length()];
		        		for(int j = 0; j < innerArray.length(); j ++) {
		        			cytassistAlignmentMatrix[i][j] = innerArray.getDouble(j);
		        		}
		        	}
		        	
		        	final double a = cytassistAlignmentMatrix[0][0];
		        	final double b = cytassistAlignmentMatrix[0][1];
		        	final double c = cytassistAlignmentMatrix[1][0];
		        	final double d = cytassistAlignmentMatrix[1][1];
		        	final double tx = cytassistAlignmentMatrix[0][2];
		        	final double ty = cytassistAlignmentMatrix[1][2];
		        	
		        	final double det = a*d-b*c;
		        	
		        	if (Math.abs(det) < 1e-9) {
		        		cytassistAlignmentMatrix = null;
		        	}
		        	else {
			        	
		        		cytassistAlignmentMatrix[0][0] = d / det;
		        		cytassistAlignmentMatrix[0][1] = -b / det;
		        		cytassistAlignmentMatrix[1][0] = -c / det;
		        		cytassistAlignmentMatrix[1][1] = a / det;
			        	
		        		cytassistAlignmentMatrix[0][2] = -(cytassistAlignmentMatrix[0][0]*tx +cytassistAlignmentMatrix[0][1]*ty);
		        		cytassistAlignmentMatrix[1][2] = -(cytassistAlignmentMatrix[1][0]*tx +cytassistAlignmentMatrix[1][1]*ty);
			        	
		        		cytassistAlignmentMatrix[2][0] = 0.0;
		        		cytassistAlignmentMatrix[2][1] = 0.0;
		        		cytassistAlignmentMatrix[2][2] = 1.0;
		        	}

		        	if(cytassistAlignmentMatrix == null) throw new Exception("cytAstTransf failed");
			        
		        }
		        
		        if(params.getBooleanParameterValue("siftAffineTransf")) {
		        	if(imgRegParamJsonObj == null) {
		        		throw new Exception("no registration_param.json available");
		        	}
		        	
		        }
		        
		        int bsplineIntervals = 0;
		        double [][]bsplineAry_x = null;
		        double [][]bsplineAry_y = null;
		        BSplineModel bsplineMdl_swx = null;
				BSplineModel bsplineMdl_swy = null;
				
		        if(params.getBooleanParameterValue("bsplineTransf")) {
		        	if(imgRegParamJsonObj == null) {
		        		throw new Exception("no registration_param.json available");
		        	}
		        	
		        	final File splineTransfFile = Paths.get(vsumAntnVsumFldrProp.get(), "direct_transf.txt").toFile();
		        	
		        	if(!splineTransfFile.exists() || !splineTransfFile.isFile()) {
		        		throw new Exception("no direct_transf.txt available");
		        	}
		        	
		        	bsplineIntervals = numberOfIntervalsOfTransformation(splineTransfFile.getAbsolutePath());
					
		        	bsplineAry_x = new double[ bsplineIntervals+3 ][ bsplineIntervals+3 ];
		        	bsplineAry_y = new double[ bsplineIntervals+3 ][ bsplineIntervals+3 ];
					loadTransformation(splineTransfFile.getAbsolutePath(), bsplineAry_x, bsplineAry_y);
					
					bsplineMdl_swx = new BSplineModel(bsplineAry_x);
					bsplineMdl_swy = new BSplineModel(bsplineAry_y);
		        }
		        
		        final Color[] palette = new Color[clsNum+1];
	    	    for(int i = 0; i < clsNum+1; i++) palette[i] = Color.getHSBColor((float) i / (float) clsNum+1, 0.85f, 1.0f);
		        
				final int rad = (int)Math.round(0.5*params.getDoubleParameterValue("spotDiameter")/imagePixelSizeMicrons);
				final int dia = (int)Math.round(params.getDoubleParameterValue("spotDiameter")/imagePixelSizeMicrons);
		        
		        Set<String> barcodeSet = spatialHMap.keySet();
				final HashMap<String, PathObject> spotToPathObjHashMap = new HashMap<>();
				
		        for(String barcode: barcodeSet) {
		        	List<Integer> list = spatialHMap.get(barcode);
		        	
		        	final int in_tissue = list.get(0);
		        	double x = list.get(4);
		        	double y = list.get(3);
		        	
		        	if(params.getBooleanParameterValue("tissueRegionsOnly") && (in_tissue == 1) || !params.getBooleanParameterValue("tissueRegionsOnly")) {
		        		final int cluster = analysisHMap.containsKey(barcode)? analysisHMap.get(barcode): 0;
						final String pathObjName = barcode;
						final String pathClsName = String.valueOf(cluster);
						

						
						if(params.getBooleanParameterValue("cytAstTransf")) {
							final double x1 = x*cytassistAlignmentMatrix[0][0]+y*cytassistAlignmentMatrix[0][1]+cytassistAlignmentMatrix[0][2];
							final double y1 = x*cytassistAlignmentMatrix[1][0]+y*cytassistAlignmentMatrix[1][1]+cytassistAlignmentMatrix[1][2];
							
							x = x1;
							y = y1;
						}
						
						if(imgRegParamJsonObj != null) {
							x *= vsumAnnotImgRegParamManualScale;
							y *= vsumAnnotImgRegParamManualScale;
							
							if(vsumAnnotImgRegParamFlipVert) {
								y = vsumAnnotImgRegParamSrcImgHeight - y;
							}
							
							if(vsumAnnotImgRegParamFlipHori) {
								x = vsumAnnotImgRegParamSrcImgWidth - x;
							}
	
							if(vsumAnnotImgRegParamRotation.equals("-90") || vsumAnnotImgRegParamRotation.equals("270")) {
								final double x1 = x;
								x = y;
								y = vsumAnnotImgRegParamSrcImgWidth - x1;
							}
							else if(vsumAnnotImgRegParamRotation.equals("-180") || vsumAnnotImgRegParamRotation.equals("180")) {
								x = vsumAnnotImgRegParamSrcImgWidth - x;
								y = vsumAnnotImgRegParamSrcImgHeight - y;
							}
							else if(vsumAnnotImgRegParamRotation.equals("-270") || vsumAnnotImgRegParamRotation.equals("90")) {
								final double x1 = x;
								x = vsumAnnotImgRegParamSrcImgHeight - y;
								y = x1;	
							}
							
							x /= vsumAnnotImgRegParamSourceScale;
							y /= vsumAnnotImgRegParamSourceScale;
							 
							if(params.getBooleanParameterValue("siftAffineTransf")) {
					        	final double x1 = vsumAnnotImgRegParamSiftMatrix[0] * x + vsumAnnotImgRegParamSiftMatrix[1] * y + vsumAnnotImgRegParamSiftMatrix[2];
					        	final double y1 = vsumAnnotImgRegParamSiftMatrix[3] * x + vsumAnnotImgRegParamSiftMatrix[4] * y + vsumAnnotImgRegParamSiftMatrix[5];
					        	
								x = x1;
								y = y1;				        	
							}
							 
							if(params.getBooleanParameterValue("bsplineTransf")) {
//								final double x1 = (double)(x * bsplineIntervals) / (double)(server.getWidth() - 1) + 1.0F;
//								final double y1 = (double)(y * bsplineIntervals) / (double)(server.getHeight() - 1) + 1.0F;
		
								final double x1 = (double)(x * bsplineIntervals) / (double)(((int)((double)server.getWidth()/vsumAnnotImgRegParamTargetScale)+0.5) - 1) + 1.0F;
								final double y1 = (double)(y * bsplineIntervals) / (double)(((int)((double)server.getHeight()/vsumAnnotImgRegParamTargetScale)+0.5) - 1) + 1.0F;
								
								
								bsplineMdl_swx.prepareForInterpolation(x1, y1, false);
								final double bspline_x_bv_bu = bsplineMdl_swx.interpolateI();
					        	
								bsplineMdl_swy.prepareForInterpolation(x1, y1, false);
								final double bspline_y_bv_bu = bsplineMdl_swy.interpolateI();
								
								x = bspline_x_bv_bu;
								y = bspline_y_bv_bu;
							}
							
							x *= vsumAnnotImgRegParamTargetScale;
							y *= vsumAnnotImgRegParamTargetScale;
							
							if(params.getBooleanParameterValue("manualShift")) {
								x += vsumAnnotImgRegParamShiftX;
								y += vsumAnnotImgRegParamShiftY;
							}
							
						}
						
						ROI pathRoi = ROIs.createEllipseROI((int)(0.5+x)-rad, (int)(0.5+y)-rad, dia, dia, null);
						
				    	final PathClass pathCls = PathClass.fromString(pathClsName);
							
				    	final PathROIObject detObj = params.getChoiceParameterValue("format").equals(formatList.get(0))? 
				    			(PathDetectionObject) PathObjects.createDetectionObject(pathRoi, pathCls):
			    				(PathAnnotationObject) PathObjects.createAnnotationObject(pathRoi, pathCls);
				    	
						detObj.setName(pathObjName);
						detObj.setColor(palette[cluster].getRGB());
				    	
						spotToPathObjHashMap.put(barcode, detObj);

						pathObjects.add(detObj);
		        	}
		        }
		         
		        final FileFilter barcodesTarGzFileFilter = new WildcardFileFilter("*filtered_feature_bc_matrix.tar.gz");
				final File[] barcodesTarGzFileList = visiumFileFolder.listFiles(barcodesTarGzFileFilter);
				
				BufferedReader barcodeGzipReader = null;
				
				if(barcodesTarGzFileList.length == 1) {
					final TarArchiveInputStream barcodesTarGzFileStream = new TarArchiveInputStream(new GzipCompressorInputStream(new FileInputStream(barcodesTarGzFileList[0])));
					TarArchiveEntry barcodesTarGzFileStreamEntry = barcodesTarGzFileStream.getNextTarEntry();	
					
					assert barcodesTarGzFileStreamEntry != null: "Opening *filtered_feature_bc_matrix.tar.gz failed.";
				
					while (barcodesTarGzFileStreamEntry != null) {
						if(barcodesTarGzFileStreamEntry.getName().equals("filtered_feature_bc_matrix/barcodes.tsv.gz")) break;
	
						barcodesTarGzFileStreamEntry = barcodesTarGzFileStream.getNextTarEntry(); // You forgot to iterate to the next file
					}
				
					assert barcodesTarGzFileStreamEntry != null: "filtered_feature_bc_matrix/barcodes.tsv.gz failed.";
				
					// Create a ByteArrayOutputStream to read the content from the .gz file
	                final ByteArrayOutputStream barcodesByteArrayOutputStream = new ByteArrayOutputStream();
	
	                // Read the content from the .gz file into the ByteArrayOutputStream
	                int barcodesCharacter;
	                while ((barcodesCharacter = barcodesTarGzFileStream.read()) != -1) {
	                	barcodesByteArrayOutputStream.write(barcodesCharacter);
	                }
                
	                final byte[] barcodesData = barcodesByteArrayOutputStream.toByteArray();
	                final ByteArrayInputStream barcodesByteArrayInputStream = new ByteArrayInputStream(barcodesData);
	                final GZIPInputStream barcodesGzipStream = new GZIPInputStream(barcodesByteArrayInputStream);
	                
	                barcodeGzipReader = new BufferedReader(new InputStreamReader(barcodesGzipStream));
				}
				else if(barcodesTarGzFileList.length == 0) {
					final String barcodeFilePath = Paths.get(vsumAntnVsumFldrProp.get(), "filtered_feature_bc_matrix", "barcodes.tsv.gz").toString();
					final FileInputStream barcodesFileInputStream = new FileInputStream(barcodeFilePath);
	                final GZIPInputStream barcodesGzipStream = new GZIPInputStream(barcodesFileInputStream);
	                
	                barcodeGzipReader = new BufferedReader(new InputStreamReader(barcodesGzipStream));
				}
				else {
					throw new Exception("Number of *filtered_feature_bc_matrix.tar.gz is wrong.");
				}
				
                final List<String> barcodeList = new ArrayList<>();
				
				String barcodeNextRecord;
				
				while ((barcodeNextRecord = barcodeGzipReader.readLine()) != null) {
					barcodeList.add(barcodeNextRecord);
				}
			
		        final FileFilter featuresTarGzFileFilter = new WildcardFileFilter("*filtered_feature_bc_matrix.tar.gz");
				final File[] featuresTarGzFileList = visiumFileFolder.listFiles(featuresTarGzFileFilter);
				
				BufferedReader featureGzipReader = null;	
				
				if(featuresTarGzFileList.length == 1) {
					final TarArchiveInputStream featuresTarGzFileStream = new TarArchiveInputStream(new GzipCompressorInputStream(new FileInputStream(featuresTarGzFileList[0])));
					TarArchiveEntry featuresTarGzFileStreamEntry = featuresTarGzFileStream.getNextTarEntry();	
					assert featuresTarGzFileStreamEntry != null: "Opening *filtered_feature_bc_matrix.tar.gz failed.";
					
					BufferedReader featuresBufferReader = null;
					
					while (featuresTarGzFileStreamEntry != null) {
						featuresBufferReader = new BufferedReader(new InputStreamReader(featuresTarGzFileStream));
						if(featuresTarGzFileStreamEntry.getName().equals("filtered_feature_bc_matrix/features.tsv.gz")) break;
						
						featuresTarGzFileStreamEntry = featuresTarGzFileStream.getNextTarEntry(); // You forgot to iterate to the next file
					}
					
					assert featuresTarGzFileStreamEntry != null: "filtered_feature_bc_matrix/features.tsv.gz failed.";
			        
			        
					// Create a ByteArrayOutputStream to read the content from the .gz file
	                final ByteArrayOutputStream featuresByteArrayOutputStream = new ByteArrayOutputStream();

	                // Read the content from the .gz file into the ByteArrayOutputStream
	                int featuresCharacter;
	                while ((featuresCharacter = featuresTarGzFileStream.read()) != -1) {
	                	featuresByteArrayOutputStream.write(featuresCharacter);
	                }
	                
	                // Convert the ByteArrayOutputStream to a byte array
	                byte[] featuresData = featuresByteArrayOutputStream.toByteArray();

	                // Create a ByteArrayInputStream using the byte array
	                ByteArrayInputStream featuresByteArrayInputStream = new ByteArrayInputStream(featuresData);

	                // Create a GZIPInputStream to decompress the data
	                final GZIPInputStream featuresGzipStream = new GZIPInputStream(featuresByteArrayInputStream);		        
	                
					featureGzipReader = new BufferedReader(new InputStreamReader(featuresGzipStream));	
				}
				else if(featuresTarGzFileList.length == 0) {
					final String featuresFilePath = Paths.get(vsumAntnVsumFldrProp.get(), "filtered_feature_bc_matrix", "features.tsv.gz").toString();
					final FileInputStream featuresFileInputStream = new FileInputStream(featuresFilePath);
	                final GZIPInputStream featuresGzipStream = new GZIPInputStream(featuresFileInputStream);
	                
	                featureGzipReader = new BufferedReader(new InputStreamReader(featuresGzipStream));
				}
				else {
					throw new Exception("Number of *filtered_feature_bc_matrix.tar.gz is wrong.");
				}
				
				final List<String> featureIdList = new ArrayList<>();
				final List<String> featureNameList = new ArrayList<>();
				final List<String> featureTypeList = new ArrayList<>();	
				
				String featureNextRecord;
				while ((featureNextRecord = featureGzipReader.readLine()) != null) {
					final String[] featureNextRecordArray = featureNextRecord.split("\t");
					featureIdList.add(featureNextRecordArray[0]);
					featureNameList.add(featureNextRecordArray[1]);
					featureTypeList.add(featureNextRecordArray[2]);
				}
				
				if(params.getBooleanParameterValue("loadTranscriptData")) {
					
					final FileFilter matrixTarGzFileFilter = new WildcardFileFilter("*filtered_feature_bc_matrix.tar.gz");
					final File[] matrixTarGzFileList = visiumFileFolder.listFiles(matrixTarGzFileFilter);
					
					
					BufferedReader matrixGzipReader = null;
					
					if(matrixTarGzFileList.length == 1) {
						final TarArchiveInputStream matrixTarGzFileStream = new TarArchiveInputStream(new GzipCompressorInputStream(new FileInputStream(matrixTarGzFileList[0])));
						TarArchiveEntry matrixTarGzFileStreamEntry = matrixTarGzFileStream.getNextTarEntry();	
						assert matrixTarGzFileStreamEntry != null: "Opening *filtered_feature_bc_matrix.tar.gz failed.";
						
						BufferedReader matrixBufferReader = null;
						
						while (matrixTarGzFileStreamEntry != null) {
							matrixBufferReader = new BufferedReader(new InputStreamReader(matrixTarGzFileStream));
							if(matrixTarGzFileStreamEntry.getName().equals("filtered_feature_bc_matrix/matrix.mtx.gz")) break;
							
							matrixTarGzFileStreamEntry = matrixTarGzFileStream.getNextTarEntry(); // You forgot to iterate to the next file
						}
						
						assert matrixTarGzFileStreamEntry != null: "filtered_feature_bc_matrix/matrix.mtx.gz failed.";
						
						// Create a ByteArrayOutputStream to read the content from the .gz file
		                final ByteArrayOutputStream matrixByteArrayOutputStream = new ByteArrayOutputStream();
		
		                // Read the content from the .gz file into the ByteArrayOutputStream
		                int matrixCharacter;
		                while ((matrixCharacter = matrixTarGzFileStream.read()) != -1) {
		                	matrixByteArrayOutputStream.write(matrixCharacter);
		                }
		                
		                // Convert the ByteArrayOutputStream to a byte array
		                final byte[] matrixData = matrixByteArrayOutputStream.toByteArray();
		
		                // Create a ByteArrayInputStream using the byte array
		                final ByteArrayInputStream matrixByteArrayInputStream = new ByteArrayInputStream(matrixData);
		
		                // Create a GZIPInputStream to decompress the data
		                final GZIPInputStream matrixGzipStream = new GZIPInputStream(matrixByteArrayInputStream);					
		                
						matrixGzipReader = new BufferedReader(new InputStreamReader(matrixGzipStream), '\t');
					}
					else if(matrixTarGzFileList.length == 0) {
						final String matrixFilePath = Paths.get(vsumAntnVsumFldrProp.get(), "filtered_feature_bc_matrix", "matrix.mtx.gz").toString();
						final FileInputStream matrixFileInputStream = new FileInputStream(matrixFilePath);
		                final GZIPInputStream matrixGzipStream = new GZIPInputStream(matrixFileInputStream);
		                
		                matrixGzipReader = new BufferedReader(new InputStreamReader(matrixGzipStream), '\t');			
					}
					else {
						throw new Exception("Number of *filtered_feature_bc_matrix.tar.gz is wrong.");
					}
					
					matrixGzipReader.readLine();
					matrixGzipReader.readLine();
					matrixGzipReader.readLine();
					
					final int[][] matrix = new int[featureNameList.size()][barcodeList.size()];
					
					String matrixNextRecord;
					while ((matrixNextRecord = matrixGzipReader.readLine()) != null) {
						final String[] matrixNextRecordArray = matrixNextRecord.split(" ");
						final int f = Integer.parseInt(matrixNextRecordArray[0])-1;
						final int b = Integer.parseInt(matrixNextRecordArray[1])-1;
						final int v = Integer.parseInt(matrixNextRecordArray[2]);
						
						matrix[f][b] = v;
					}
			        
			        IntStream.range(0, barcodeList.size()).parallel().forEach(b -> {
						if(spotToPathObjHashMap.containsKey(barcodeList.get(b))) {
					    	final PathObject c = spotToPathObjHashMap.get(barcodeList.get(b));
					    	final MeasurementList pathObjMeasList = c.getMeasurementList();
					    	
					    	for(int f = 0; f < featureNameList.size(); f ++) {	
								pathObjMeasList.put("transcript:"+featureNameList.get(f), matrix[f][b]); 
					    	}
					    	
					    	pathObjMeasList.close();
						}
					}); 			
				}

			}
			catch(Exception e) {	
				Dialogs.showErrorMessage("Error", e.getMessage());
				lastResults =  e.getMessage();
				logger.error(e.getMessage());				
			}
			
			if (Thread.currentThread().isInterrupted())
				return null;
			
			if (pathObjects == null || pathObjects.isEmpty())
				lastResults =  "No regions detected!";
			else if (pathObjects.size() == 1)
				lastResults =  "1 region detected";
			else
				lastResults =  pathObjects.size() + " regions detected";
			
			logger.info(lastResults);
			
			return pathObjects;
		}
		
		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
	}
	
	
	@Override
	protected void preprocess(final TaskRunner taskRunner, final ImageData<BufferedImage> imageData) {
		if(params.getStringParameterValue("visiumDir").isBlank()) {
		
			final File vsumDir = FileChoosers.promptForDirectory("Visium output directory", new File(vsumAntnVsumFldrProp.get()));
			
			if (vsumDir != null) {
				vsumAntnVsumFldrProp.set(vsumDir.toString());
			}
			else {
				Dialogs.showErrorMessage("Warning", "No Visium directory is selected!");
				lastResults =  "No Visium directory is selected!";
				logger.warn(lastResults);
			}
		}
		else {
			vsumAntnVsumFldrProp.set(params.getStringParameterValue("visiumDir"));
		}
	};
	
	
	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		// boolean micronsKnown = imageData.getServer().getPixelCalibration().hasPixelSizeMicrons();
		// params.setHiddenParameters(!micronsKnown, "requestedPixelSizeMicrons", "minAreaMicrons", "maxHoleAreaMicrons");
		// params.setHiddenParameters(micronsKnown, "requestedDownsample", "minAreaPixels", "maxHoleAreaPixels");
		return params;
	}

	@Override
	public String getName() {
		return "10x Genomics Visium V2 (CytAssist) Annotation Loader";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}

	@Override
	public String getDescription() {
		return "Detect one or more regions of interest by applying a global threshold";
	}


	@Override
	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
		tasks.add(DetectionPluginTools.createRunnableTask(new AnnotationLoader(), getParameterList(imageData), imageData, parentObject));
	}

	@Override
	protected Collection<? extends PathObject> getParentObjects(final ImageData<BufferedImage> imageData) {
		
		PathObjectHierarchy hierarchy = imageData.getHierarchy();
		if (hierarchy.getTMAGrid() == null)
			return Collections.singleton(hierarchy.getRootObject());
		
		return hierarchy.getSelectionModel().getSelectedObjects().stream().filter(p -> p.isTMACore()).collect(Collectors.toList());
	}

	@Override
	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		// TODO: Re-allow taking an object as input in order to limit bounds
		// Temporarily disabled so as to avoid asking annoying questions when run repeatedly
		List<Class<? extends PathObject>> list = new ArrayList<>();
		list.add(TMACoreObject.class);

		list.add(PathRootObject.class);
		return list;
	}
}
