/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2023 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.qust;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.StringProperty;
import qupath.ext.stardist.StarDist2D;
import qupath.ext.stardist.StarDist2D.Builder;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.plugins.AbstractInteractivePlugin;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;

/**
 * Experimental plugin to help with the quantification of stardist cell nuclei structures.
 * 
 * @author Chao Hui Huang
 *
 */
public class StarDistCellNucleusDetection extends AbstractInteractivePlugin<BufferedImage> {
	protected static QuSTSetup qustSetup = QuSTSetup.getInstance();
	private static final Logger logger = LoggerFactory.getLogger(StarDistCellNucleusDetection.class);
	private ParameterList params;
	
	/**
	 * Default constructor.
	 */
	public StarDistCellNucleusDetection() {
		List<String> stardistModeNamelList;
		try {
			
			final StringProperty stardistModelLocationPathProp = PathPrefs.createPersistentPreference("stardistModelLocationPath", "");
			stardistModeNamelList = Files.list(Paths.get(stardistModelLocationPathProp.get()))
				    .filter(Files::isRegularFile)
				    .map(p -> p.getFileName().toString())
				    .collect(Collectors.toList());

			params = new ParameterList()
				.addTitleParameter("General Parameters")			
				.addDoubleParameter("threshold", "Probability (detection) threshold", 0.1, null, "Probability (detection) threshold")
				.addDoubleParameter("normalizePercentilesLow", "Percentile normalization (lower bound)", 1, null, "Percentile normalization (lower bound)")
				.addDoubleParameter("normalizePercentilesHigh", "Percentile normalization (higher bound)", 99, null, "Percentile normalization (lower bound)")
				.addTitleParameter("Measurements")
				.addBooleanParameter("includeProbability", "Add probability as a measurement (enables later filtering). Default: false", false, "Add probability as a measurement (enables later filtering)")
				.addBooleanParameter("measureShape", "Add shape measurements. Default: false", false, "Add shape measurements")
				.addBooleanParameter("measureIntensity", "Add intensity measurements. Default: false", false, "Add shape measurements")
				.addTitleParameter("Additional Parameters")
				.addChoiceParameter("starDistModel", "Specify the model .pb file", stardistModeNamelList .get(0), stardistModeNamelList, "Choose the model that should be used for object classification")
				.addStringParameter("channel", "Select detection channel (e.g., DAPI. Default: [empty] = N/A)", "")
				.addDoubleParameter("cellExpansion", "Approximate cells based upon nucleus expansion (e.g., 5.0. Default: -1 = N/A)", -1, null, "Approximate cells based upon nucleus expansion")		
				.addDoubleParameter("cellConstrainScale", "Constrain cell expansion using nucleus size (e.g., 1.5. Default: -1 = N/A)", -1, null, "Constrain cell expansion using nucleus size")
				.addIntParameter("nThreads", "stardist n threads (default: 0, using stardist default setting)", 0, null, "stardist N thread (default: 0, using stardist default setting)")		
				.addIntParameter("tileSize", "stardist tile size (default: 0, using stardist default setting)", 0, null, "stardist tile size (default: 0, using stardist default setting)")	
				;
		
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	@Override
	public boolean runPlugin(final TaskRunner taskRunner, final ImageData<BufferedImage> imageData, final String arg) {
		boolean success = super.runPlugin(taskRunner, imageData, arg);
		imageData.getHierarchy().fireHierarchyChangedEvent(this);
		return success;
	}
	
	
	@Override
	protected void addRunnableTasks(final ImageData<BufferedImage> imageData, final PathObject parentObject, List<Runnable> tasks) {
		final ParameterList params = getParameterList(imageData);
		
		tasks.add(new StarDistCellNucleusRunnable(imageData, parentObject, params));
	}
	
	
	static class StarDistCellNucleusRunnable implements Runnable {
		
		private ImageData<BufferedImage> imageData;
		private ParameterList params;
		private PathObject parentObject;
		
		public StarDistCellNucleusRunnable(final ImageData<BufferedImage> imageData, final PathObject parentObject, final ParameterList params) {
			this.imageData = imageData;
			this.parentObject = parentObject;
			this.params = params;
		}

		@Override
		public void run() {
			try {
				if (parentObject instanceof PathAnnotationObject)
					processObject(parentObject, params, imageData);
			} catch (InterruptedException e) {
				logger.error("Processing interrupted", e);
			} catch (IOException e) {
				logger.error("Error processing " + parentObject, e);
			} finally {
				parentObject.getMeasurementList().close();
				imageData = null;
				params = null;
			}
		}
		
		@Override
		public String toString() {
			// TODO: Give a better toString()
			return "stardist cell nuclei detection";
		}
		
	}
	
	/**
	 * Initial version of stardist cell nuclei detection processing.
	 * 
	 * @param pathObject
	 * @param params
	 * @param imageWrapper
	 * @return
	 * @throws InterruptedException
	 * @throws IOException 
	 */
	static boolean processObject(final PathObject pathObject, final ParameterList params, final ImageData<BufferedImage> imageData) throws InterruptedException, IOException {
		final String modelFilePath = (String)params.getChoiceParameterValue("starDistModel");
		final double threshold = params.getDoubleParameterValue("threshold");
		final String channels = params.getStringParameterValue("channel");
		final double normalizePercentilesLow = params.getDoubleParameterValue("normalizePercentilesLow");
		final double normalizePercentilesHigh = params.getDoubleParameterValue("normalizePercentilesHigh");
		
		final ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();		
		final double pixelSize = server.getPixelCalibration().getAveragedPixelSizeMicrons();
		final double cellExpansion = params.getDoubleParameterValue("cellExpansion");
		final double cellConstrainScale = params.getDoubleParameterValue("cellConstrainScale");
		final boolean measureShape = params.getBooleanParameterValue("measureShape");
		final boolean measureIntensity = params.getBooleanParameterValue("measureIntensity");
		final boolean includeProbability = params.getBooleanParameterValue("includeProbability");
		final int nThreads = params.getIntParameterValue("nThreads");
		final int tileSize = params.getIntParameterValue("tileSize");
		
		final List<PathObject> parentObjects = new ArrayList<PathObject>();
		parentObjects.add(pathObject);

		final Path stardistModelPath = Paths.get(qustSetup.getStardistModelLocationPath(), modelFilePath);
		
		final Builder stardistBuilder = StarDist2D.builder(stardistModelPath.toString())
		        .threshold(threshold)
		        .normalizePercentiles(normalizePercentilesLow, normalizePercentilesHigh)
		        .pixelSize(pixelSize);
        if(!channels.isBlank()) stardistBuilder.channels(channels);
        if(cellExpansion > 0) stardistBuilder.cellExpansion(cellExpansion);
        if(cellConstrainScale > 0) stardistBuilder.cellConstrainScale(cellConstrainScale);
		if(measureShape) stardistBuilder.measureShape();
		if(measureIntensity) stardistBuilder.measureIntensity();
		if(includeProbability) stardistBuilder.includeProbability(true);
		if(nThreads > 0) stardistBuilder.nThreads(nThreads);
		if(tileSize > 0) stardistBuilder.tileSize(tileSize);
		final StarDist2D stardist = stardistBuilder.build();
		
		stardist.detectObjects((ImageData<BufferedImage>) imageData, parentObjects);
		
		return true;
	}
	

	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		return params;
	}

	@Override
	public String getName() {
		return "stardist cell nucleus detection";
	}

	@Override
	public String getLastResultsDescription() {
		return "";
	}

	@Override
	public String getDescription() {
		return "Run stardist cell nucleus detection";
	}

	@Override
	protected Collection<PathObject> getParentObjects(final ImageData<BufferedImage> imageData) {
		Collection<Class<? extends PathObject>> parentClasses = getSupportedParentObjectClasses();
		List<PathObject> parents = new ArrayList<>();
		for (PathObject parent : imageData.getHierarchy().getSelectionModel().getSelectedObjects()) {
			for (Class<? extends PathObject> cls : parentClasses) {
				if (cls.isAssignableFrom(parent.getClass())) {
					parents.add(parent);
					break;
				}
			}
		}
		return parents;
	}

	@Override
	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		List<Class<? extends PathObject>> parents = new ArrayList<>();
		parents.add(TMACoreObject.class);
		parents.add(PathAnnotationObject.class);
//		parents.add(PathCellObject.class);
		return parents;
	}
	

}
