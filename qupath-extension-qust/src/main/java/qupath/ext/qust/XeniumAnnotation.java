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
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.StringTokenizer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.StringProperty;
import javafx.geometry.Point2D;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.interfaces.ROI;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
/**
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class XeniumAnnotation extends AbstractDetectionPlugin<BufferedImage> {
	
	final private static Logger logger = LoggerFactory.getLogger(XeniumAnnotation.class);
	
	final private StringProperty xnumAntnXnumFldrProp = PathPrefs.createPersistentPreference("xnumAntnXnumFldr", ""); 
	
	private ParameterList params = null;

	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public XeniumAnnotation() {	
		params = new ParameterList()
			.addTitleParameter("10X Xenium Data Loader")
			.addStringParameter("xeniumDir", "Xenium directory", xnumAntnXnumFldrProp.get(), "Xenium Out Directory")
			.addEmptyParameter("")
			.addBooleanParameter("dontTransform", "DO NOT transform? (default: false)", false, "DO NOT transform? (default: false)")		
			.addBooleanParameter("AffineTransformOnly", "Affine (linear) transform ONLY? (default: false)", false, "Affine (linear) transform ONLY? (default: false)")		
			.addBooleanParameter("removeUnlabeledCells", "Remove unlabeled cells? (default: true)", true, "Remove unlabeled cells? (default: true)")		
			.addBooleanParameter("inclGeneExpr", "Include Gene Expression? (default: true)", true, "Include Gene Expression? (default: true)")		
			.addBooleanParameter("inclBlankCodeword", "Include Blank Codeword? (default: false)", false, "Include Blank Codeword? (default: false)")		
			.addBooleanParameter("inclNegCtrlCodeword", "Include Negative Control Codeword? (default: false)", false, "Include Negative Control Codeword? (default: false)")		
			.addBooleanParameter("inclNegCtrlProbe", "Include Negative Control Probe? (default: false)", false, "Include Negative Control Probe? (default: false)")		
			.addEmptyParameter("")
			.addIntParameter("maskDownsampling", "Downsampling for transcript to cell assignment", 2, null, "Downsampling for cell-transciptome assignment")			
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		//------------------------------------------------------------------
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

		
		/* --------------------------------------------------------------------*/
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
			final ImageServer<BufferedImage> server = imageData.getServer();				
			final PathObjectHierarchy hierarchy = imageData.getHierarchy();
			final ArrayList<PathObject> resultPathObjectList = new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			
			try {
				// Load linear transformation
				
				
				final InputStream is = params.getBooleanParameterValue("dontTransform")? null: new FileInputStream(Paths.get(xnumAntnXnumFldrProp.get(), "affine_matrix.json").toString());
				final String jsonTxt = params.getBooleanParameterValue("dontTransform")? null: IOUtils.toString(is, "UTF-8");
				final JSONObject jsonObj = params.getBooleanParameterValue("dontTransform")? null: new JSONObject(jsonTxt);    
				final double pixelSizeMicrons = server.getPixelCalibration().getAveragedPixelSizeMicrons();
		        final double dapiImagePixelSizeMicrons = params.getBooleanParameterValue("dontTransform")? pixelSizeMicrons: jsonObj.getDouble("dapi_pixel_size");
				final int dapiImageImageSeries = params.getBooleanParameterValue("dontTransform")? 1: jsonObj.getInt("dapi_image_series");
				final double[] affineMtx = params.getBooleanParameterValue("dontTransform")? null: IntStream.range(0, 6).mapToDouble(i -> jsonObj.getJSONArray("affine_matrix").getDouble(i)).toArray();
				
				// Load nonlinear transformation
				
				final String transf_file = !params.getBooleanParameterValue("AffineTransformOnly")? Paths.get(xnumAntnXnumFldrProp.get(), "direct_transf.txt").toString(): null;
				
				int bspline_intervals = transf_file != null? numberOfIntervalsOfTransformation(transf_file): 0;
				double [][]bspline_cx = new double[ bspline_intervals+3 ][ bspline_intervals+3 ];
				double [][]bspline_cy = new double[ bspline_intervals+3 ][ bspline_intervals+3 ];
				if (transf_file != null) loadTransformation( transf_file, bspline_cx, bspline_cy );
				
				// Compute the deformation
				// Set these coefficients to an interpolator
				final BSplineModel bspline_swx = transf_file != null? new BSplineModel(bspline_cx): null;
				final BSplineModel bspline_swy = transf_file != null? new BSplineModel(bspline_cy): null;
				
	            /*
	             * Generate cell masks with their labels
	             */
				
				final List<PathObject> selectedAnnotationPathObjectList = hierarchy
						.getSelectionModel()
						.getSelectedObjects()
						.stream()
						.filter(e -> e.isAnnotation() && e.hasChildObjects())
						.collect(Collectors.toList());
				
				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");
				
				final int maskDownsampling = params.getIntParameterValue("maskDownsampling");;
				final int maskWidth = (int)Math.round(server.getWidth()/maskDownsampling);
				final int maskHeight = (int)Math.round(server.getHeight()/maskDownsampling);	
				
				final BufferedImage annotPathObjectImageMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_RGB);
				final List<PathObject> annotPathObjectList = new ArrayList<PathObject>();						
				
				final Graphics2D annotPathObjectG2D = annotPathObjectImageMask.createGraphics();				
				annotPathObjectG2D.setBackground(new Color(0, 0, 0));
				annotPathObjectG2D.clearRect(0, 0, maskWidth, maskHeight);
				
				annotPathObjectG2D.setClip(0, 0, maskWidth, maskHeight);
				annotPathObjectG2D.scale(1.0/maskDownsampling, 1.0/maskDownsampling);					    
				
				final BufferedImage pathObjectImageMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_RGB);
				final List<PathObject> pathObjectList = new ArrayList<PathObject>();						
				
				final Graphics2D pathObjectG2D = pathObjectImageMask.createGraphics();				
				pathObjectG2D.setBackground(new Color(0, 0, 0));
				pathObjectG2D.clearRect(0, 0, maskWidth, maskHeight);
				
				pathObjectG2D.setClip(0, 0, maskWidth, maskHeight);
				pathObjectG2D.scale(1.0/maskDownsampling, 1.0/maskDownsampling);
				
				try {
					int annotPathObjectCount = 1;
					int pathObjectCount = 1;
					
					for(PathObject p: selectedAnnotationPathObjectList) {
						annotPathObjectList.add(p);
					    
					    final int pb0 = (annotPathObjectCount & 0xff) >> 0; // b
					    final int pb1 = (annotPathObjectCount & 0xff00) >> 8; // g
					    final int pb2 = (annotPathObjectCount & 0xff0000) >> 16; // r
					    final Color pMaskColor = new Color(pb2, pb1, pb0); // r, g, b
				    
					    final ROI pRoi = p.getROI();
						final Shape pShape = pRoi.getShape();
						
						annotPathObjectG2D.setColor(pMaskColor);
						annotPathObjectG2D.fill(pShape);
						
						annotPathObjectCount ++;
					    if(annotPathObjectCount == 0xffffff) {
					    	throw new Exception("annotation count overflow!");
					    }
						
						for(PathObject c: p.getChildObjects()) {
							pathObjectList.add(c);
						    
						    final int b0 = (pathObjectCount & 0xff) >> 0; // b
						    final int b1 = (pathObjectCount & 0xff00) >> 8; // g
						    final int b2 = (pathObjectCount & 0xff0000) >> 16; // r
						    final Color maskColor = new Color(b2, b1, b0); // r, g, b
					    
						    final ROI roi = c.getROI();
							final Shape shape = roi.getShape();
							
							pathObjectG2D.setColor(maskColor);
							pathObjectG2D.fill(shape);
							
							pathObjectCount ++;
						    if(pathObjectCount == 0xffffff) {
						    	throw new Exception("Cell count overflow!");
						    }
						}
					}	
				}
				catch(Exception e) {
					throw e;
				}
				finally {
					annotPathObjectG2D.dispose();	
					pathObjectG2D.dispose();	
				}
				
	            /*
	             * Read single cell data
	             * "cell_id","x_centroid","y_centroid","transcript_counts","control_probe_counts","control_codeword_counts","total_counts","cell_area","nucleus_area"
	             */

				if(xnumAntnXnumFldrProp.get().isBlank()) throw new Exception("singleCellFile is blank");
				
				final HashMap<String, Integer> cellToClusterHashMap = new HashMap<>();
				
				final String clusterFilePath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "analysis", "clustering", "gene_expression_graphclust", "clusters.csv").toString();
				final FileReader clusterFileReader = new FileReader(new File(clusterFilePath));
				final BufferedReader clusterReader = new BufferedReader(clusterFileReader);
				clusterReader.readLine();
				String clusterNextRecord;
				
				while ((clusterNextRecord = clusterReader.readLine()) != null) {
		        	final String[] clusterNextRecordArray = clusterNextRecord.split(",");
		        	final String cellId = clusterNextRecordArray[0].replaceAll("\"", "");
		        	final int clusterId = Integer.parseInt(clusterNextRecordArray[1]);
		        	cellToClusterHashMap.put(cellId, clusterId);
				}
				
				clusterReader.close();
				
				final HashMap<String, String> cellToSCLabelHashMap = new HashMap<>();
				
				
//				final String analysisFilePath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "analysis.tar.gz").toString();	
//				TarArchiveInputStream analysisTarInput = new TarArchiveInputStream(new GzipCompressorInputStream(new FileInputStream(analysisFilePath)));
//				TarArchiveEntry analysisCurrentEntry = analysisTarInput.getNextTarEntry();
//				BufferedReader analysisBufferReader = null;
//				
//				// StringBuilder analysisStringBuilder = new StringBuilder();
//				while (analysisCurrentEntry != null) {
//					analysisBufferReader = new BufferedReader(new InputStreamReader(analysisTarInput)); // Read directly from tarInput
//				    System.out.println("For File = " + analysisCurrentEntry.getName());
//				    String line;
//				    while ((line = analysisBufferReader.readLine()) != null) {
//				        System.out.println("line="+line);
//				    }
//				    analysisCurrentEntry = analysisTarInput.getNextTarEntry(); // You forgot to iterate to the next file
//				}
				
				
				
				
				
				// final String analysisFilePath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "analysis.tar.gz").toString();
				// final GZIPInputStream analysisGzipStream = new GZIPInputStream(new FileInputStream(analysisFilePath));
				
				
				
				final String scLabelFilePath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "analysis", "clustering", "gene_expression_graphclust", "clusters.csv").toString();
				final FileReader scLabelFileReader = new FileReader(new File(scLabelFilePath));
				final BufferedReader scLabelReader = new BufferedReader(scLabelFileReader);
				scLabelReader.readLine();
				String scLabelNextRecord;
				
				while ((scLabelNextRecord = scLabelReader.readLine()) != null) {
		        	final String[] scLabelNextRecordArray = scLabelNextRecord.split(",");
		        	final String cellId = scLabelNextRecordArray[0].replaceAll("\"", "");;
		        	final String scLabelId = scLabelNextRecordArray[1].replaceAll("\"", "");;
		        	cellToSCLabelHashMap.put(cellId, scLabelId);
				}
				
				scLabelReader.close();
				
				final HashMap<String, PathObject> cellToPathObjHashMap = new HashMap<>();
			
				final String singleCellFilePath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "cells.csv.gz").toString();
				final GZIPInputStream singleCellGzipStream = new GZIPInputStream(new FileInputStream(singleCellFilePath));
				final BufferedReader singleCellGzipReader = new BufferedReader(new InputStreamReader(singleCellGzipStream));
				singleCellGzipReader.readLine();
				String singleCellNextRecord;
				
		        while ((singleCellNextRecord = singleCellGzipReader.readLine()) != null) {
		        	final String[] singleCellNextRecordArray = singleCellNextRecord.split(",");
		        	final String cellId = singleCellNextRecordArray[0].replaceAll("\"", "");
		        	
		        	final double transcriptCounts = Double.parseDouble(singleCellNextRecordArray[3]);
		        	final double controlProbeCounts = Double.parseDouble(singleCellNextRecordArray[4]);
		        	final double controlCodewordCounts = Double.parseDouble(singleCellNextRecordArray[5]);
		        	final double totalCounts = Double.parseDouble(singleCellNextRecordArray[6]);
		        	final double cellArea = Double.parseDouble(singleCellNextRecordArray[7]);
		        	final double nucleusArea = Double.parseDouble(singleCellNextRecordArray[8]);
		        	
		        	final double cx = Double.parseDouble(singleCellNextRecordArray[1]);
		        	final double cy = Double.parseDouble(singleCellNextRecordArray[2]);
		        	
		        	final double dx = cx/dapiImagePixelSizeMicrons;
		        	final double dy = cy/dapiImagePixelSizeMicrons;
		        	
		        	int bx = 0;
		        	int by = 0;
		        	
		        	if(params.getBooleanParameterValue("dontTransform")) {
		        		bx = (int)Math.round(dx);
		        		by = (int)Math.round(dy);
		        	}
		        	else {
			        	final double scale = Math.pow(2.0, (double)(dapiImageImageSeries-1.0));
			        	final double ax = affineMtx[0] * dx + affineMtx[1] * dy + affineMtx[2] * scale;
			        	final double ay = affineMtx[3] * dx + affineMtx[4] * dy + affineMtx[5] * scale;
						
			        	if(!params.getBooleanParameterValue("AffineTransformOnly")) {
				        	final int bv = (int)Math.round(ay);
				        	final int bu = (int)Math.round(ax);
				        	
							final double tu = (double)(bu * bspline_intervals) / (double)(server.getWidth() - 1) + 1.0F;
							final double tv = (double)(bv * bspline_intervals) / (double)(server.getHeight() - 1) + 1.0F;
							
							bspline_swx.prepareForInterpolation(tu, tv, false);
							final double bspline_x_bv_bu = bspline_swx.interpolateI();
				        	
							bspline_swy.prepareForInterpolation(tu, tv, false);
							final double bspline_y_bv_bu = bspline_swy.interpolateI();  
				        	
							bx = (int)Math.round(scale * bspline_x_bv_bu);
							by = (int)Math.round(scale * bspline_y_bv_bu);
			        	}
			        	else {
				        	bx = (int)Math.round(ax);
			        		by = (int)Math.round(ay);
			        	}
		        	}
		        	
		        	final int fx = (int)Math.round(bx / maskDownsampling);
		        	final int fy = (int)Math.round(by / maskDownsampling);
		        	
		        	if(fx < 0 || fx >= pathObjectImageMask.getWidth() || fy < 0 || fy >=  pathObjectImageMask.getHeight()) continue;
		        	
		        	final int v = pathObjectImageMask.getRGB(fx, fy);
		        	final int d0 = v&0xff;
		        	final int d1 = (v>>8)&0xff;
		        	final int d2 = (v>>16)&0xff;
					final int r = d2*0x10000+d1*0x100+d0;
				    
		        	if(r == 0) continue; // This location doesn't have a cell.
			        	
		        	final int pathObjectId = r - 1;  // pathObjectId starts at 1, since 0 means background
			        	
		        	final PathObject cellPathObject = pathObjectList.get(pathObjectId);
		        	cellToPathObjHashMap.put(cellId, cellPathObject);
		        	
		        	final String scLabelId = cellToSCLabelHashMap.get(cellId);
		        	
		        	if(scLabelId != null) {
		        		final PathClass pathCls = PathClass.fromString(scLabelId);
			        	cellPathObject.setPathClass(pathCls);
		        	}
		        	
		        	final double roiX = cellPathObject.getROI().getCentroidX();
		        	final double roiY = cellPathObject.getROI().getCentroidY();
		        	final double newDist = (new Point2D(bx, by).distance(roiX, roiY))*pixelSizeMicrons;
		        	final MeasurementList pathObjMeasList = cellPathObject.getMeasurementList();
		        	
		        	if(pathObjMeasList.containsKey("xenium:cell:cell_id")) {
		        		final double minDist = pathObjMeasList.get("xenium:cell:displacement");
		        		if(newDist < minDist) {
		        			cellPathObject.setName(cellId);
		        			pathObjMeasList.put("xenium:cell:displacement", newDist);
		        			pathObjMeasList.put("xenium:cell:x_centroid", cx);
		        			pathObjMeasList.put("xenium:cell:y_centroid", cy);
		        			pathObjMeasList.put("xenium:cell:transcript_counts", transcriptCounts);
		        			pathObjMeasList.put("xenium:cell:control_probe_counts", controlProbeCounts);
		        			pathObjMeasList.put("xenium:cell:control_codeword_counts", controlCodewordCounts);
		        			pathObjMeasList.put("xenium:cell:total_counts", totalCounts);
		        			pathObjMeasList.put("xenium:cell:cell_area", cellArea);
		        			pathObjMeasList.put("xenium:cell:nucleus_area", nucleusArea);
		        		}
		        	}
		        	else {
		        		cellPathObject.setName(cellId);
	        			pathObjMeasList.put("xenium:cell:displacement", newDist);
	        			pathObjMeasList.put("xenium:cell:x_centroid", cx);
	        			pathObjMeasList.put("xenium:cell:y_centroid", cy);
	        			pathObjMeasList.put("xenium:cell:transcript_counts", transcriptCounts);
	        			pathObjMeasList.put("xenium:cell:control_probe_counts", controlProbeCounts);
	        			pathObjMeasList.put("xenium:cell:control_codeword_counts", controlCodewordCounts);
	        			pathObjMeasList.put("xenium:cell:total_counts", totalCounts);
	        			pathObjMeasList.put("xenium:cell:cell_area", cellArea);
	        			pathObjMeasList.put("xenium:cell:nucleus_area", nucleusArea);     		        
		        	}
		        	
		        	pathObjMeasList.close(); 
	        	}		        	
	        	
		        singleCellGzipReader.close();
				
				/*
	             * Read feature matrix data
	             */
					
				final String barcodeFilePath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "cell_feature_matrix", "barcodes.tsv.gz").toString();
				final String featureFilePath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "cell_feature_matrix", "features.tsv.gz").toString();
				final String matrixFilePath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "cell_feature_matrix", "matrix.mtx.gz").toString();
				
				final GZIPInputStream barcodeGzipStream = new GZIPInputStream(new FileInputStream(barcodeFilePath));
				try (BufferedReader barcodeGzipReader = new BufferedReader(new InputStreamReader(barcodeGzipStream))) {
					final List<String> barcodeList = new ArrayList<>();
					
					String barcodeNextRecord;
					while ((barcodeNextRecord = barcodeGzipReader.readLine()) != null) {
						barcodeList.add(barcodeNextRecord);
					}
					
					final List<String> featureIdList = new ArrayList<>();
					final List<String> featureNameList = new ArrayList<>();
					final List<String> featureTypeList = new ArrayList<>();
					
					final GZIPInputStream featureGzipStream = new GZIPInputStream(new FileInputStream(featureFilePath));
					try (BufferedReader featureGzipReader = new BufferedReader(new InputStreamReader(featureGzipStream))) {
						String featureNextRecord;
						while ((featureNextRecord = featureGzipReader.readLine()) != null) {
							final String[] featureNextRecordArray = featureNextRecord.split("\t");
							featureIdList.add(featureNextRecordArray[0]);
							featureNameList.add(featureNextRecordArray[1]);
							featureTypeList.add(featureNextRecordArray[2]);
						}
					}
					
					final GZIPInputStream matrixGzipStream = new GZIPInputStream(new FileInputStream(matrixFilePath));
					try (BufferedReader matrixGzipReader = new BufferedReader(new InputStreamReader(matrixGzipStream), '\t')) {
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
//						for(int b = 0; b < barcodeList.size(); b ++) {
							if(cellToPathObjHashMap.containsKey(barcodeList.get(b))) {
						    	final PathObject c = cellToPathObjHashMap.get(barcodeList.get(b));
						    	final MeasurementList pathObjMeasList = c.getMeasurementList();
						    	
						    	for(int f = 0; f < featureNameList.size(); f ++) {	
						    		if(!params.getBooleanParameterValue("inclBlankCodeword") && (featureTypeList.get(f).compareTo("Blank Codeword") == 0 || featureTypeList.get(f).compareTo("Unassigned Codeword") == 0)) continue;
									if(!params.getBooleanParameterValue("inclGeneExpr") && (featureTypeList.get(f).compareTo("Gene Expression") == 0)) continue;
									if(!params.getBooleanParameterValue("inclNegCtrlCodeword") && (featureTypeList.get(f).compareTo("Negative Control Codeword") == 0)) continue;
									if(!params.getBooleanParameterValue("inclNegCtrlProbe") && (featureTypeList.get(f).compareTo("Negative Control Probe") == 0)) continue;
						    		
									pathObjMeasList.put("transcript:"+featureNameList.get(f), matrix[f][b]);  
						    			 
						    	}
						    	
						    	pathObjMeasList.close();
							}
						});
//						}
					}
				}
				
				if(params.getBooleanParameterValue("removeUnlabeledCells")) {
					for(PathObject c: pathObjectList) {
						if(c.getPathClass() == null) {
							c.getParent().removeChildObject(c);
						}
					}
				}

		        hierarchy.getSelectionModel().setSelectedObject(null);
			}
			catch(Exception e) {	
				Dialogs.showErrorMessage("Error", e.getMessage());
				
				lastResults =  "Something went wrong: "+e.getMessage();
				logger.error(lastResults);
				return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			}				
			
			if (Thread.currentThread().isInterrupted()) {
				Dialogs.showErrorMessage("Warning", "Interrupted!");
				lastResults =  "Interrupted!";
				logger.warn(lastResults);
				
				return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			}
			
			return resultPathObjectList;
		}
		
		
		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
		
		
	}

	@Override
	protected void preprocess(final TaskRunner taskRunner, final ImageData<BufferedImage> imageData) {
		if(params.getStringParameterValue("xeniumDir").isBlank()) {
		
			final File xnumDir = Dialogs.promptForDirectory("Xenium directory", new File(xnumAntnXnumFldrProp.get()));
			
			if (xnumDir != null) {
				xnumAntnXnumFldrProp.set(xnumDir.toString());
			}
			else {
				Dialogs.showErrorMessage("Warning", "No Xenium directory is selected!");
				lastResults =  "No Xenium directory is selected!";
				logger.warn(lastResults);
			}
		}
		else {
			xnumAntnXnumFldrProp.set(params.getStringParameterValue("xeniumDir"));
		}
	};
	
	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		return params;
	}

	@Override
	public String getName() {
		return "Simple tissue detection";
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

		return Arrays.asList(
			PathAnnotationObject.class,
			TMACoreObject.class
			);		
	}

}
