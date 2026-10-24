/*-
 * #%L
 * bUnwarpJ plugin for Fiji.
 * %%
 * Copyright (C) 2005 - 2020 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package qupath.ext.qust;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;




public class MacenkoStainingNormalizer {
//	public double[][] targetStainReferenceMatrix = {
//	       {0.65, 0.70, 0.29},   // Hematoxylin
//	       {0.07, 0.99, 0.11}    // Eosin
//	};
	public static double[][] targetStainReferenceMatrix = {
	//   Eos,  Hema, None
		{0.07, 0.65, 0.00},   
		{0.99, 0.70, 0.00},   
		{0.11, 0.29, 0.00},
	};

	public static BufferedImage concatBufferedImages(List<BufferedImage> images) {
		int totalHeight = images.stream().mapToInt(BufferedImage::getHeight).sum();
		int maxWidth = images.stream().mapToInt(BufferedImage::getWidth).max().orElse(0);
		
		BufferedImage result = new BufferedImage(maxWidth, totalHeight, images.get(0).getType());
		Graphics2D g2d = result.createGraphics();
		int currentY = 0;
		for (BufferedImage img : images) {
			g2d.drawImage(img, 0, currentY, null);
			currentY += img.getHeight();
		}
		g2d.dispose();
		return result;
	}
	
	public static double[][][] convertImageToRGBArray(BufferedImage inputImage) {
		int width = inputImage.getWidth();
		int height = inputImage.getHeight();
		double[][][] rgbData = new double[height][width][3];
		IntStream.range(0, height).parallel().forEach(y -> {
			IntStream.range(0, width).parallel().forEach(x -> {
				int rgb = inputImage.getRGB(x, y);
				rgbData[y][x][0] = (rgb >> 16) & 0xff;
				rgbData[y][x][1] = (rgb >> 8) & 0xff;
				rgbData[y][x][2] = rgb & 0xff;
			});
		});
		
		return rgbData;
	}
	
	public static BufferedImage convertRGBArrayToImage(double[][][] inputArray, int imageType) {
		
		int height = inputArray.length;
		int width = inputArray[0].length;
		
		BufferedImage result = new BufferedImage(width, height, imageType);
	   
		for(int y = 0; y < height; y ++) {
			for (int x = 0; x < width; x ++) {
				result.setRGB(x, y, ((int)inputArray[y][x][0] << 16) | ((int)inputArray[y][x][1] << 8) | (int)inputArray[y][x][2]);
			}
		}
		
		return result;
	}
	
	
    /**
     * Transform input RGB image or matrix into SDA (stain darkness) space 
     * for color deconvolution.
     * 
     * @param imRgb Image (MxNx3) or matrix (3xN) of pixels
     * @param I0 Background intensity, either per-channel or for all channels
     * @param allowNegatives If false, would-be negative values in the output are clipped to 0
     * @return Array shaped like imRgb, with output values 0..255 where imRgb >= 1
     */
    public static double[][][] rgbToSda(double[][][] imRgb, double I0, boolean allowNegatives) {
        if (imRgb == null) {
            throw new IllegalArgumentException("Input image cannot be null");
        }
        
        // Create output array with same dimensions
        int height = imRgb.length;
        int width = imRgb[0].length;
        int channels = imRgb[0][0].length;
        double[][][] imSda = new double[height][width][channels];
        
        // Handle I_0 = null case for rgb_to_od compatibility
        double backgroundIntensity = I0;
        double[][][] processedRgb = new double[height][width][channels];
        
        if (I0 == 0) { // Using 0 to represent None/null case
            backgroundIntensity = 256.0;
            // Add 1 to all values for compatibility
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    for (int k = 0; k < channels; k++) {
                        processedRgb[i][j][k] = imRgb[i][j][k] + 1.0;
                    }
                }
            }
        } else {
            // Copy the original array
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    for (int k = 0; k < channels; k++) {
                        processedRgb[i][j][k] = imRgb[i][j][k];
                    }
                }
            }
        }
        
        // Ensure all values are at least 1e-10 (equivalent to np.maximum)
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                for (int k = 0; k < channels; k++) {
                    processedRgb[i][j][k] = Math.max(processedRgb[i][j][k], 1e-10);
                }
            }
        }
        
        // Apply SDA transformation: -log(im_rgb / I_0) * 255 / log(I_0)
        double logI0 = Math.log(backgroundIntensity);
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                for (int k = 0; k < channels; k++) {
                    double value = -Math.log(processedRgb[i][j][k] / backgroundIntensity) * 255.0 / logI0;
                    
                    if (!allowNegatives) {
                        value = Math.max(value, 0.0);
                    }
                    
                    imSda[i][j][k] = value;
                }
            }
        }
        
        return imSda;
    }
    
    /**
     * Convenience method with default allowNegatives = false
     */
    public static double[][][] rgbToSda(double[][][] imRgb, double I0) {
        return rgbToSda(imRgb, I0, false);
    }
        
    /**
     * Convenience method with default I0 = 255.0 and allowNegatives = false
     */
    public static double[][][] rgbToSda(double[][][] imRgb) {
        return rgbToSda(imRgb, 255.0, false);
    }
    
    /**
     * Compute the stain matrix for color deconvolution with the Macenko method.
     * 
     * For a two-stain image or matrix in SDA space, this method works by
     * computing a best-fit plane with PCA, wherein it selects the stain
     * vectors as percentiles in the "angle distribution" in that plane.
     * 
     * @param imSda Image (MxNx3) or matrix (3xN) in SDA space
     * @param minimumMagnitude Magnitude below which vectors are excluded from angle computation
     * @param minAnglePercentile Smaller percentile to pick from angle distribution
     * @param maxAnglePercentile Larger percentile to pick from angle distribution
     * @param maskOut Optional boolean mask to exclude areas from calculations
     * @return A 3x3 matrix of stain column vectors
     */
    public static double[][] separateStainsMacenkoPca(
            double[][][] imSda, 
            double minimumMagnitude,
            double minAnglePercentile, 
            double maxAnglePercentile,
            boolean[][][] maskOut) {
        
        // Convert 3D image to 2D matrix
        double[][] m = convertImageToMatrix(imSda);
        
        // Apply mask if provided
        if (maskOut != null) {
            m = applyMask3D(m, imSda, maskOut);
        }
        
        // Continue with same logic as 2D version
        m = excludeNonfinite(m);
        double[][] pcs = getPrincipalComponents(m);
        double[][] proj = projectToPCAPlane(pcs, m);
        double[][] filt = filterByMagnitude(proj, minimumMagnitude);
        double[] angles = getAngles(filt);
        
        double[] minV = getPercentileVector(pcs, filt, angles, minAnglePercentile);
        double[] maxV = getPercentileVector(pcs, filt, angles, maxAnglePercentile);
        
        double[][] stainVectors = new double[3][2];
        for (int i = 0; i < 3; i++) {
            stainVectors[i][0] = minV[i];
            stainVectors[i][1] = maxV[i];
        }
        
        stainVectors = normalizeColumns(stainVectors);
        return complementStainMatrix(stainVectors);
    }
    
    /**
     * Convenience methods with default parameters
     */
    public static double[][] separateStainsMacenkoPca(double[][][] imSda) {
        return separateStainsMacenkoPca(imSda, 16.0, 0.01, 0.99, null);
    }

    
    /**
     * Convert 3D image to 2D matrix (3xN format)
     */
    private static double[][] convertImageToMatrix(double[][][] image) {
        int height = image.length;
        int width = image[0].length;
        int channels = image[0][0].length;
        int totalPixels = height * width;
        
        double[][] matrix = new double[channels][totalPixels];
        
        int pixelIndex = 0;
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                for (int c = 0; c < channels; c++) {
                    matrix[c][pixelIndex] = image[i][j][c];
                }
                pixelIndex++;
            }
        }
        
        return matrix;
    }

    
    /**
     * Apply 3D mask to matrix data
     */
    private static double[][] applyMask3D(double[][] m, double[][][] imSda, boolean[][][] maskOut) {
        int height = imSda.length;
        int width = imSda[0].length;
        int channels = imSda[0][0].length;
        
        List<Integer> validIndices = new ArrayList<>();
        int pixelIndex = 0;
        
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                boolean keepPixel = true;
                for (int c = 0; c < channels; c++) {
                    if (maskOut[i][j][c]) {
                        keepPixel = false;
                        break;
                    }
                }
                if (keepPixel) {
                    validIndices.add(pixelIndex);
                }
                pixelIndex++;
            }
        }
        
        double[][] filtered = new double[channels][validIndices.size()];
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < validIndices.size(); i++) {
                filtered[c][i] = m[c][validIndices.get(i)];
            }
        }
        
        return filtered;
    }

    
    /**
     * Remove NaN and infinite values from matrix
     */
    private static double[][] excludeNonfinite(double[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        
        List<Integer> validCols = new ArrayList<>();
        
        for (int j = 0; j < cols; j++) {
            boolean isValid = true;
            for (int i = 0; i < rows; i++) {
                if (Double.isNaN(matrix[i][j]) || Double.isInfinite(matrix[i][j])) {
                    isValid = false;
                    break;
                }
            }
            if (isValid) {
                validCols.add(j);
            }
        }
        
        double[][] filtered = new double[rows][validCols.size()];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < validCols.size(); j++) {
                filtered[i][j] = matrix[i][validCols.get(j)];
            }
        }
        
        return filtered;
    }
 


 

   
    
   /** Analogue of numpy.linalg.svd(A, full_matrices)                                    */
   public static SvdResult svd(double[][] a, boolean fullMatrices) {
       RealMatrix A = MatrixUtils.createRealMatrix(a);
       SingularValueDecomposition svd = new SingularValueDecomposition(A);
       RealMatrix U  = svd.getU();          // m × m
       RealMatrix Vt = svd.getVT();         // n × n
       double[]  S   = svd.getSingularValues();   // length k
       if (!fullMatrices) {                 // reduce to k = min(m,n)
           int k = S.length,
               m = A.getRowDimension(),
               n = A.getColumnDimension();
           U  = U .getSubMatrix(0, m - 1, 0, k - 1);   // m × k
           Vt = Vt.getSubMatrix(0, k - 1, 0, n - 1);   // k × n
       }
       return new SvdResult(U.getData(), S, Vt.getData());
   }
   public static SvdResult svd(double[][] a) {
	   return svd(a, false);
   }
   /** Simple record-like container */
   public static class SvdResult {
       public final double[][] U;   // rows = left singular vectors
       public final double[]   S;   // singular values
       public final double[][] Vh;  // = Vᵀ   (right singular vectors)
       public SvdResult(double[][] U, double[] S, double[][] Vh) {
           this.U = U; this.S = S; this.Vh = Vh;
       }
   }
    
    
    
    
    
    
    
    
    
   
    
    private static double[][] getPrincipalComponents(double[][] m) {
    	SvdResult result = svd(m);
    	return result.U;
    }

  
     
    /**
     * Project matrix onto PCA plane (first 2 components)
     */
    private static double[][] projectToPCAPlane(double[][] pcs, double[][] matrix) {
        int cols = matrix[0].length;
        double[][] proj = new double[2][cols];
        
        // Project onto first 2 principal components
        for (int j = 0; j < cols; j++) {
            for (int pc = 0; pc < 2; pc++) {
                double sum = 0.0;
                for (int i = 0; i < matrix.length; i++) {
                    sum += pcs[i][pc] * matrix[i][j];
                }
                proj[pc][j] = sum;
            }
        }
        
        return proj;
    }
    
    /**
     * Filter vectors by magnitude threshold
     */
    private static double[][] filterByMagnitude(double[][] proj, double threshold) {
        int cols = proj[0].length;
        List<Integer> validIndices = new ArrayList<>();
        
        for (int j = 0; j < cols; j++) {
            double magnitude = Math.sqrt(proj[0][j] * proj[0][j] + proj[1][j] * proj[1][j]);
            if (magnitude > threshold) {
                validIndices.add(j);
            }
        }
        
        double[][] filtered = new double[2][validIndices.size()];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < validIndices.size(); j++) {
                filtered[i][j] = proj[i][validIndices.get(j)];
            }
        }
        
        return filtered;
    }
    
    /**
     * Calculate angles for 2xN matrix of vectors
     */
    private static double[] getAngles(double[][] m) {
        // Normalize the vectors first
        double[][] normalized = normalizeVectors(m);
        int cols = normalized[0].length;
        double[] angles = new double[cols];
        
        // "Angle" towards +x from the +y axis: (1 - m[1]) * sign(m[0])
        for (int j = 0; j < cols; j++) {
            angles[j] = (1.0 - normalized[1][j]) * Math.signum(normalized[0][j]);
        }
        
        return angles;
    }
    
    /**
     * Normalize vectors (columns) in a matrix
     */
    private static double[][] normalizeVectors(double[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        double[][] normalized = new double[rows][cols];
        
        for (int j = 0; j < cols; j++) {
            double norm = 0.0;
            for (int i = 0; i < rows; i++) {
                norm += matrix[i][j] * matrix[i][j];
            }
            norm = Math.sqrt(norm);
            
            if (norm > 1e-10) {
                for (int i = 0; i < rows; i++) {
                    normalized[i][j] = matrix[i][j] / norm;
                }
            }
        }
        
        return normalized;
    }
    
    /**
     * Get vector at specified percentile
     */
    private static double[] getPercentileVector(double[][] pcs, double[][] filt, 
                                              double[] angles, double percentile) {
        int index = argPercentile(angles, percentile);
        double[] vector = new double[pcs.length];
        
        // Multiply pcs[:, :-1] with filt[:, index]
        for (int i = 0; i < pcs.length; i++) {
            double sum = 0.0;
            for (int j = 0; j < pcs[0].length - 1; j++) {
                sum += pcs[i][j] * filt[j][index];
            }
            vector[i] = sum;
        }
        
        return vector;
    }
    
    /**
     * Calculate index of element nearest the pth percentile
     */
    private static int argPercentile(double[] arr, double p) {
        int i = Math.min((int)(p * arr.length + 0.5), arr.length - 1);
        
        // Simple implementation - in practice would use more efficient partitioning
        double[] sorted = arr.clone();
        Arrays.sort(sorted);
        double targetValue = sorted[i];
        
        // Find index of this value in original array
        for (int j = 0; j < arr.length; j++) {
            if (Math.abs(arr[j] - targetValue) < 1e-10) {
                return j;
            }
        }
        
        return i;
    }
    
    /**
     * Normalize columns of a matrix
     */
    private static double[][] normalizeColumns(double[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        double[][] normalized = new double[rows][cols];
        
        for (int j = 0; j < cols; j++) {
            double norm = 0.0;
            for (int i = 0; i < rows; i++) {
                norm += matrix[i][j] * matrix[i][j];
            }
            norm = Math.sqrt(norm);
            
            if (norm > 1e-10) {
                for (int i = 0; i < rows; i++) {
                    normalized[i][j] = matrix[i][j] / norm;
                }
            }
        }
        
        return normalized;
    }
    
    /**
     * Complement stain matrix by adding orthogonal third column
     */
    private static double[][] complementStainMatrix(double[][] stainMatrix) {
        // This is a simplified implementation
        // The actual complement_stain_matrix function would:
        // 1. Take the cross product of the first two columns
        // 2. Normalize the result
        // 3. Add it as the third column
        
        double[][] complemented = new double[3][3];
        
        // Copy existing columns
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < Math.min(2, stainMatrix[0].length); j++) {
                complemented[i][j] = stainMatrix[i][j];
            }
        }
        
        // Compute cross product for third column
        double[] col1 = {stainMatrix[0][0], stainMatrix[1][0], stainMatrix[2][0]};
        double[] col2 = {stainMatrix[0][1], stainMatrix[1][1], stainMatrix[2][1]};
        
        double[] crossProduct = {
            col1[1] * col2[2] - col1[2] * col2[1],
            col1[2] * col2[0] - col1[0] * col2[2],
            col1[0] * col2[1] - col1[1] * col2[0]
        };
        
        // Normalize cross product
        double norm = Math.sqrt(crossProduct[0] * crossProduct[0] + 
                               crossProduct[1] * crossProduct[1] + 
                               crossProduct[2] * crossProduct[2]);
        
        if (norm > 1e-10) {
            for (int i = 0; i < 3; i++) {
                complemented[i][2] = crossProduct[i] / norm;
            }
        }
        
        return complemented;
    }    
    

		
    // Stain color map - idealized stain vectors
    private static final Map<String, double[]> STAIN_COLOR_MAP = new HashMap<>();
    
    static {
        // Initialize with common stain vectors (these are example values)
        STAIN_COLOR_MAP.put("hematoxylin", new double[]{0.65, 0.70, 0.29});
        STAIN_COLOR_MAP.put("eosin", new double[]{0.07, 0.99, 0.11});
        STAIN_COLOR_MAP.put("dab", new double[]{0.27, 0.57, 0.78});
        STAIN_COLOR_MAP.put("null", new double[]{0.0, 0.0, 0.0});
    }
    
    /**
     * Perform color normalization using color deconvolution to transform the
     * color characteristics of an image to a desired standard.
     * 
     * @param imSrc RGB image (m x n x 3) to color normalize
     * @param wSource 3x3 matrix of source stain column vectors (optional)
     * @param wTarget 3x3 matrix of target stain column vectors (optional)
     * @param imTarget RGB image with good color properties to transfer (optional)
     * @param stains List of stain names (order important), default is H&E
     * @param maskOut Boolean mask to exclude areas from calculations (optional)
     * @param stainUnmixingParams Parameters for stain unmixing routine
     * @return Color normalized RGB image (m x n x 3)
     */
    public static double[][][] deconvolutionBasedNormalization(
            double[][][] imSrc,
            double[][] wSource,
            double[][] wTarget,
            double[][][] imTarget,
            String[] stains,
            boolean[][] maskOut,
            Map<String, Object> stainUnmixingParams) {
    	
    	// Set default stains if not provided
        if (stains == null) {
            stains = new String[]{"hematoxylin", "eosin"};
        }
        
        // Set default parameters if not provided
        if (stainUnmixingParams == null) {
            stainUnmixingParams = new HashMap<>();
        }
        
        // Validate parameters
        if (stainUnmixingParams.containsKey("W_source") || 
            stainUnmixingParams.containsKey("mask_out")) {
            throw new IllegalArgumentException(
                "W_source and mask_out must be provided as separate parameters");
        }
        
        // Add stains to unmixing parameters
        stainUnmixingParams.put("stains", stains);
        
        // Find stains matrix from source image using color deconvolution
        ColorDeconvolutionResult deconvResult = colorDeconvolutionRoutine(
            imSrc, wSource, maskOut, stainUnmixingParams);
        double[][][] stainsFloat = deconvResult.getStainsFloat();
        
        // Get W_target
        double[][] finalWTarget = getTargetStainMatrix(
            wTarget, imTarget, stains, stainUnmixingParams);
        
        // Convolve source image StainsFloat with W_target
        double[][][] imSrcNormalized = colorConvolution(stainsFloat, finalWTarget);
        
        // Apply mask if provided - return masked values using unnormalized image
        if (maskOut != null) {
            imSrcNormalized = applyMaskToNormalizedImage(
                imSrc, imSrcNormalized, maskOut);
        }
        
        return imSrcNormalized;
    }
    
    /**
     * Convenience method with default parameters
     */
    public static double[][][] deconvolutionBasedNormalization(double[][][] imSrc) {
        return deconvolutionBasedNormalization(
            imSrc, null, null, null, null, null, null);
    }
    
    /**
     * Convenience method with stains specification
     */
    public static double[][][] deconvolutionBasedNormalization(
            double[][][] imSrc, String[] stains) {
        return deconvolutionBasedNormalization(
            imSrc, null, null, null, stains, null, null);
    }
    
    /**
     * Convenience method with mask
     */
    public static double[][][] deconvolutionBasedNormalization(
            double[][][] imSrc, String[] stains, boolean[][] maskOut) {
        return deconvolutionBasedNormalization(
            imSrc, null, null, null, stains, maskOut, null);
    }
    
    /**
     * Get the target stain matrix based on provided parameters
     */
    private static double[][] getTargetStainMatrix(
            double[][] wTarget, 
            double[][][] imTarget, 
            String[] stains,
            Map<String, Object> stainUnmixingParams) {
        
        if (wTarget == null && imTarget == null) {
            // Normalize to 'ideal' stain matrix if none is provided
            double[][] idealMatrix = new double[3][2];
            
            // Get stain vectors from color map
            for (int i = 0; i < Math.min(stains.length, 2); i++) {
                double[] stainVector = STAIN_COLOR_MAP.get(stains[i]);
                if (stainVector != null) {
                    for (int j = 0; j < 3; j++) {
                        idealMatrix[j][i] = stainVector[j];
                    }
                }
            }
            
            // Complement the stain matrix to make it 3x3
            return complementStainMatrix(idealMatrix);
            
        } else if (imTarget != null) {
            // Get W_target from target image
            return stainUnmixingRoutine(imTarget, stainUnmixingParams);
            
        } else {
            // Use provided W_target
            return wTarget;
        }
    }
    
    /**
     * Apply mask to normalized image, keeping original values in masked areas
     */
    private static double[][][] applyMaskToNormalizedImage(
            double[][][] imSrc, 
            double[][][] imSrcNormalized, 
            boolean[][] maskOut) {
        
        int height = imSrc.length;
        int width = imSrc[0].length;
        double[][][] result = new double[height][width][3];
        
        // Copy normalized image
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                for (int c = 0; c < 3; c++) {
                    result[i][j][c] = imSrcNormalized[i][j][c];
                }
            }
        }
        
        // Apply mask for each channel
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    if (maskOut[i][j]) {
                        // Use original value for masked pixels
                        result[i][j][c] = imSrc[i][j][c];
                    }
                }
            }
        }
        
        return result;
    }
    
    /**
     * Perform color deconvolution routine
     */
    private static ColorDeconvolutionResult colorDeconvolutionRoutine(
            double[][][] imSrc, 
            double[][] wSource, 
            boolean[][] maskOut,
            Map<String, Object> params) {
        
        // Convert image to SDA space
        double[][][] imSda = rgbToSda(imSrc, 255.0);
        
        // Get stain matrix
        double[][] stainMatrix;
        if (wSource != null) {
            stainMatrix = wSource;
        } else {
            // Use stain unmixing to estimate stain matrix
            stainMatrix = stainUnmixingRoutine(imSrc, params);
        }
        
        // Perform deconvolution to get stain concentrations
        double[][][] stainsFloat = stainUnmixingWithMatrix(imSda, stainMatrix);
        
        return new ColorDeconvolutionResult(imSda, stainsFloat, stainMatrix);
    }
    
    /**
     * Stain unmixing routine - estimates stain matrix from image
     */
    private static double[][] stainUnmixingRoutine(
            double[][][] image, 
            Map<String, Object> params) {
        
        // Convert to SDA space
        double[][][] imSda = rgbToSda(image, 255.0);
        
        // Use Macenko method for stain separation
        // This calls the previously implemented MacenkoPCA class
        return separateStainsMacenkoPca(imSda);
    }
    
    /**
     * Perform stain unmixing using known stain matrix
     */
    private static double[][][] stainUnmixingWithMatrix(
            double[][][] imSda, 
            double[][] stainMatrix) {
        
        int height = imSda.length;
        int width = imSda[0].length;
        double[][][] stains = new double[height][width][3];
        
        // Compute pseudo-inverse of stain matrix
        double[][] stainMatrixInv = pseudoInverse(stainMatrix);
        
        // Apply unmixing to each pixel
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                double[] pixel = {imSda[i][j][0], imSda[i][j][1], imSda[i][j][2]};
                double[] stainConcentrations = matrixVectorMultiply(stainMatrixInv, pixel);
                
                for (int c = 0; c < 3; c++) {
                    stains[i][j][c] = stainConcentrations[c];
                }
            }
        }
        
        return stains;
    }
    
    /**
     * Color convolution - convert stain concentrations back to RGB
     */
    private static double[][][] colorConvolution(
            double[][][] stainsFloat, 
            double[][] wTarget) {
        
        int height = stainsFloat.length;
        int width = stainsFloat[0].length;
        double[][][] result = new double[height][width][3];
        
        // Apply color convolution to each pixel
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                double[] stainConcs = {
                    stainsFloat[i][j][0], 
                    stainsFloat[i][j][1], 
                    stainsFloat[i][j][2]
                };
                
                // Multiply stain concentrations by target stain matrix
                double[] rgbSda = matrixVectorMultiply(wTarget, stainConcs);
                
                // Convert back to RGB space
                for (int c = 0; c < 3; c++) {
                    // Convert from SDA back to RGB: RGB = I_0 * exp(-SDA * log(I_0) / 255)
                    double I_0 = 255.0;
                    result[i][j][c] = I_0 * Math.exp(-rgbSda[c] * Math.log(I_0) / 255.0);
                    
                    // Clamp to valid RGB range
                    result[i][j][c] = Math.max(0.0, Math.min(255.0, result[i][j][c]));
                }
            }
        }
        
        return result;
    }
    

    
    /**
     * Compute pseudo-inverse of a matrix (simplified implementation)
     */
    private static double[][] pseudoInverse(double[][] matrix) {
        // This is a simplified implementation
        // For production use, consider using Apache Commons Math
        int rows = matrix.length;
        int cols = matrix[0].length;
        
        if (rows == cols) {
            // Square matrix - attempt regular inverse
            return invertMatrix(matrix);
        } else {
            // Non-square matrix - use transpose method
            double[][] transpose = transposeMatrix(matrix);
            double[][] product = matrixMultiply(transpose, matrix);
            double[][] inverse = invertMatrix(product);
            return matrixMultiply(inverse, transpose);
        }
    }
    
    /**
     * Simple matrix inversion for 3x3 matrices
     */
    private static double[][] invertMatrix(double[][] matrix) {
        int n = matrix.length;
        double[][] inverse = new double[n][n];
        
        if (n == 3) {
            // Specific implementation for 3x3 matrix
            double det = determinant3x3(matrix);
            if (Math.abs(det) < 1e-10) {
                throw new IllegalArgumentException("Matrix is singular");
            }
            
            inverse[0][0] = (matrix[1][1] * matrix[2][2] - matrix[1][2] * matrix[2][1]) / det;
            inverse[0][1] = (matrix[0][2] * matrix[2][1] - matrix[0][1] * matrix[2][2]) / det;
            inverse[0][2] = (matrix[0][1] * matrix[1][2] - matrix[0][2] * matrix[1][1]) / det;
            
            inverse[1][0] = (matrix[1][2] * matrix[2][0] - matrix[1][0] * matrix[2][2]) / det;
            inverse[1][1] = (matrix[0][0] * matrix[2][2] - matrix[0][2] * matrix[2][0]) / det;
            inverse[1][2] = (matrix[0][2] * matrix[1][0] - matrix[0][0] * matrix[1][2]) / det;
            
            inverse[2][0] = (matrix[1][0] * matrix[2][1] - matrix[1][1] * matrix[2][0]) / det;
            inverse[2][1] = (matrix[0][1] * matrix[2][0] - matrix[0][0] * matrix[2][1]) / det;
            inverse[2][2] = (matrix[0][0] * matrix[1][1] - matrix[0][1] * matrix[1][0]) / det;
        } else {
            // For other sizes, use identity as fallback (should use proper library)
            for (int i = 0; i < n; i++) {
                inverse[i][i] = 1.0;
            }
        }
        
        return inverse;
    }
    
    /**
     * Calculate determinant of 3x3 matrix
     */
    private static double determinant3x3(double[][] matrix) {
        return matrix[0][0] * (matrix[1][1] * matrix[2][2] - matrix[1][2] * matrix[2][1])
             - matrix[0][1] * (matrix[1][0] * matrix[2][2] - matrix[1][2] * matrix[2][0])
             + matrix[0][2] * (matrix[1][0] * matrix[2][1] - matrix[1][1] * matrix[2][0]);
    }
    
    /**
     * Matrix transpose
     */
    private static double[][] transposeMatrix(double[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        double[][] transpose = new double[cols][rows];
        
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                transpose[j][i] = matrix[i][j];
            }
        }
        
        return transpose;
    }
    
    /**
     * Matrix multiplication
     */
    private static double[][] matrixMultiply(double[][] a, double[][] b) {
        int rowsA = a.length;
        int colsA = a[0].length;
        int colsB = b[0].length;
        
        double[][] result = new double[rowsA][colsB];
        
        for (int i = 0; i < rowsA; i++) {
            for (int j = 0; j < colsB; j++) {
                for (int k = 0; k < colsA; k++) {
                    result[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        
        return result;
    }
    
    /**
     * Matrix-vector multiplication
     */
    private static double[] matrixVectorMultiply(double[][] matrix, double[] vector) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        double[] result = new double[rows];
        
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                result[i] += matrix[i][j] * vector[j];
            }
        }
        
        return result;
    }
    
    /**
     * Result class for color deconvolution
     */
    public static class ColorDeconvolutionResult {
        private final double[][][] imSda;
        private final double[][][] stainsFloat;
        private final double[][] stainMatrix;
        
        public ColorDeconvolutionResult(double[][][] imSda, double[][][] stainsFloat, double[][] stainMatrix) {
            this.imSda = imSda;
            this.stainsFloat = stainsFloat;
            this.stainMatrix = stainMatrix;
        }
        
        public double[][][] getImSda() { return imSda; }
        public double[][][] getStainsFloat() { return stainsFloat; }
        public double[][] getStainMatrix() { return stainMatrix; }
    }
}




//public class MacenkoStainingNormalizer {
//	public double[][] targetStainReferenceMatrix = {
//           {0.65, 0.70, 0.29},   // Hematoxylin
//           {0.07, 0.99, 0.11}    // Eosin
//	};
//	
//	public double OD_threshold = 0.15;   // Tissue filtering threshold
//	
//	public BufferedImage normalizeToReferenceImage(BufferedImage inputImage, double[][] est_W, double[][] ref_W, int image_type) {
//		BufferedImage standardized = standardizeBrightness(inputImage, image_type);
//		int width = standardized.getWidth();
//		int height = standardized.getHeight();
//		int N = width * height;
//
//		double[][] odMatrix = new double[N][3];
//		for (int y = 0; y < height; y++) {
//			for (int x = 0; x < width; x++) {
//				int rgb = standardized.getRGB(x, y);
//				int r = (rgb >> 16) & 0xff;
//				int g = (rgb >> 8) & 0xff;
//				int b = rgb & 0xff;
//				odMatrix[y * width + x] = new double[] {
//						-Math.log((r + 1.0) / 256.0),
//						-Math.log((g + 1.0) / 256.0),
//						-Math.log((b + 1.0) / 256.0)
//				};
//			}
//		}
//       
//		double[][] concentrations = getConcentrations(odMatrix, est_W);
//		double[] maxCSource = percentilePerColumn(concentrations, 99);
//		double[] maxCTarget = percentilePerColumn(generateTargetConcentrations(N, ref_W), 99);
//       
//		for (int i = 0; i < N; i++) {
//			for (int j = 0; j < 2; j++) {
//				concentrations[i][j] *= (maxCTarget[j] / maxCSource[j]);
//			}
//		}
//       
//		RealMatrix C = MatrixUtils.createRealMatrix(concentrations);
//		RealMatrix Wref = MatrixUtils.createRealMatrix(ref_W);
//		RealMatrix ODNew = C.multiply(Wref);
//		BufferedImage result = new BufferedImage(width, height, image_type);
//       
//		IntStream.range(0, N).parallel().forEach(i -> {
//			double[] od = ODNew.getRow(i);
//			int[] rgb = new int[3];
//			for (int j = 0; j < 3; j++) {
//				rgb[j] = Math.max(0, Math.min(255, (int) Math.round(255 * Math.exp(-od[j]))));
//			}
//			int x = i % width;
//			int y = i / width;
//			synchronized (result) {
//				result.setRGB(x, y, (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]);
//			}
//		});
//       
//		return result;
//	}
//	
//	public double[][] getStainMatrix(BufferedImage image, double beta, double alpha) {
//		int width = image.getWidth();
//		int height = image.getHeight();
//		double[][][] rgbData = new double[height][width][3];
//		IntStream.range(0, height).parallel().forEach(y -> {
//			IntStream.range(0, width).parallel().forEach(x -> {
//				int rgb = image.getRGB(x, y);
//				rgbData[y][x][0] = (rgb >> 16) & 0xff;
//				rgbData[y][x][1] = (rgb >> 8) & 0xff;
//				rgbData[y][x][2] = rgb & 0xff;
//			});
//		});
//       
//		List<double[]> ODList = Collections.synchronizedList(new ArrayList<>());
//		IntStream.range(0, height).parallel().forEach(y -> {
//			IntStream.range(0, width).parallel().forEach(x -> {
//				double[] rgb = rgbData[y][x];
//				double[] od = new double[3];
//				IntStream.range(0, 3).parallel().forEach(c -> {
//					od[c] = -Math.log((rgb[c] + 1.0) / 256.0);
//				});
//				if (od[0] > beta || od[1] > beta || od[2] > beta) {
//					ODList.add(od);
//				}
//			});
//		});
//		double[][] ODArray = ODList.toArray(new double[0][0]);
//		RealMatrix OD = MatrixUtils.createRealMatrix(ODArray);
//		Covariance cov = new Covariance(OD);
//		RealMatrix covMatrix = cov.getCovarianceMatrix();
//		EigenDecomposition eig = new EigenDecomposition(covMatrix);
//		RealMatrix V = MatrixUtils.createRealMatrix(3, 2);
//		double[] eigenVals = eig.getRealEigenvalues();
//		Integer[] idx = {0, 1, 2};
//		Arrays.sort(idx, Comparator.comparingDouble(i -> eigenVals[i]));
//		int i1 = idx[2];
//		int i2 = idx[1];
//		RealVector v1 = eig.getEigenvector(i1);
//		RealVector v2 = eig.getEigenvector(i2);
//		if (v1.getEntry(0) < 0) v1 = v1.mapMultiply(-1);
//		if (v2.getEntry(0) < 0) v2 = v2.mapMultiply(-1);
//		V.setColumnVector(0, v1);
//		V.setColumnVector(1, v2);
//		RealMatrix That = OD.multiply(V);
//		double[] phi = new double[That.getRowDimension()];
//		IntStream.range(0, phi.length).parallel().forEach(i -> {
//			double x = That.getEntry(i, 0);
//			double y = That.getEntry(i, 1);
//			phi[i] = Math.atan2(y, x);
//		});
//		Arrays.sort(phi);
//		double minPhi = percentile(phi, alpha);
//		double maxPhi = percentile(phi, 100 - alpha);
//		double[] v1hat = new double[3];
//		double[] v2hat = new double[3];
//		IntStream.range(0, 3).parallel().forEach(i -> {
//			v1hat[i] = V.getEntry(i, 0) * Math.cos(minPhi) + V.getEntry(i, 1) * Math.sin(minPhi);
//			v2hat[i] = V.getEntry(i, 0) * Math.cos(maxPhi) + V.getEntry(i, 1) * Math.sin(maxPhi);
//		});
//		double[][] HE;
//		if (v1hat[0] > v2hat[0]) {
//			HE = new double[][]{normalize(v1hat), normalize(v2hat)};
//		} else {
//			HE = new double[][]{normalize(v2hat), normalize(v1hat)};
//		}
//		return HE;
//	}
//      
//	   
//	public BufferedImage concatBufferedImages(List<BufferedImage> images) {
//		int totalHeight = images.stream().mapToInt(BufferedImage::getHeight).sum();
//		int maxWidth = images.stream().mapToInt(BufferedImage::getWidth).max().orElse(0);
//		
//		BufferedImage result = new BufferedImage(maxWidth, totalHeight, images.get(0).getType());
//		Graphics2D g2d = result.createGraphics();
//		int currentY = 0;
//		for (BufferedImage img : images) {
//			g2d.drawImage(img, 0, currentY, null);
//			currentY += img.getHeight();
//		}
//		g2d.dispose();
//		return result;
//	}
//   
//	private static double[][] getConcentrations(double[][] ODMatrix, double[][] stainMatrix) {
//		int numPixels = ODMatrix.length;
//		double[][] concentrations = new double[numPixels][2];
//		RealMatrix D = MatrixUtils.createRealMatrix(stainMatrix).transpose();
//		IntStream.range(0, numPixels).parallel().forEach(i -> {
//			RealVector x = new ArrayRealVector(ODMatrix[i]);
//			concentrations[i] = solveNNLS(D, x);
//		});
//		return concentrations;
//	}
//   
//	private static double[] solveNNLS(RealMatrix D, RealVector x) {
//		int maxIter = 100;
//		double tol = 1e-4;
//		double alpha = 1.0;
//		RealVector c = new ArrayRealVector(D.getColumnDimension());
//		for (int iter = 0; iter < maxIter; iter++) {
//			RealVector grad = D.transpose().operate(D.operate(c).subtract(x));
//			RealVector cNew = c.subtract(grad.mapMultiply(alpha));
//			for (int i = 0; i < cNew.getDimension(); i++) {
//				cNew.setEntry(i, Math.max(0, cNew.getEntry(i)));
//			}
//			if (c.getDistance(cNew) < tol) break;
//			c = cNew;
//		}
//		return c.toArray();
//	}
//	
//
//	private static double[] normalize(double[] v) {
//		double norm = Math.sqrt(Arrays.stream(v).map(x -> x * x).sum());
//		return Arrays.stream(v).map(x -> x / norm).toArray();
//	}
//	
//	private static double percentile(double[] values, double p) {
//		int index = (int) Math.round(p / 100.0 * (values.length - 1));
//		return values[Math.max(0, Math.min(index, values.length - 1))];
//	}
//	
//	private static double[] percentilePerColumn(double[][] matrix, double percentile) {
//		int cols = matrix[0].length;
//		double[] result = new double[cols];
//		IntStream.range(0, cols).parallel().forEach(j -> {
//			double[] column = new double[matrix.length];
//			IntStream.range(0, matrix.length).parallel().forEach(i -> {
//				column[i] = matrix[i][j];
//			});
//			Arrays.sort(column);
//			int index = (int) Math.round((percentile / 100.0) * (column.length - 1));
//			result[j] = column[Math.min(index, column.length - 1)];
//		});
//		return result;
//	}
//	private static double cosineSimilarity(double[] a, double[] b) {
//		double dot = 0, normA = 0, normB = 0;
//		for (int i = 0; i < a.length; i++) {
//			dot += a[i] * b[i];
//			normA += a[i] * a[i];
//			normB += b[i] * b[i];
//		}
//		return dot / (Math.sqrt(normA) * Math.sqrt(normB));
//	}
//	
//	double[][] reorderStainsByCosineSimilarity(double[][] estW, double[][] refW) {
//		double sim0 = cosineSimilarity(estW[0], refW[0]);
//		double sim1 = cosineSimilarity(estW[1], refW[0]);
//		return sim0 >= sim1 ? estW : new double[][]{estW[1], estW[0]};
//	}
//	
//	private static double[][] generateTargetConcentrations(int N, double[][] ref_W) {
//		double[][] C = new double[N][2];
//		for (int i = 0; i < N; i++) {
//			C[i][0] = 1.0;
//			C[i][1] = 1.0;
//		}
//		return C;
//	}
//	
//	private static BufferedImage standardizeBrightness(BufferedImage image, int image_type) {
//		int width = image.getWidth();
//		int height = image.getHeight();
//		int[] intensityValues = new int[width * height * 3];
//
//		AtomicInteger index = new AtomicInteger(0);	
//		IntStream.range(0, height).parallel().forEach(y -> {
//			IntStream.range(0, width).parallel().forEach(x -> {
//				int rgb = image.getRGB(x, y);
//				intensityValues[index.getAndIncrement()] = (rgb >> 16) & 0xff;
//				intensityValues[index.getAndIncrement()] = (rgb >> 8) & 0xff;
//				intensityValues[index.getAndIncrement()] = rgb & 0xff;
//				
//			});
//		});
//		Arrays.sort(intensityValues);
//		int p90Index = (int) (0.9 * intensityValues.length);
//		double p90 = intensityValues[Math.min(p90Index, intensityValues.length - 1)];
//		double scale = 255.0 / p90;
//		BufferedImage output = new BufferedImage(width, height, image_type);
//		IntStream.range(0, height).parallel().forEach(y -> {
//			IntStream.range(0, width).parallel().forEach(x -> {
//				int rgb = image.getRGB(x, y);
//				int r = Math.min(255, Math.max(0, (int) Math.round(((rgb >> 16) & 0xff) * scale)));
//				int g = Math.min(255, Math.max(0, (int) Math.round(((rgb >> 8) & 0xff) * scale)));
//				int b = Math.min(255, Math.max(0, (int) Math.round((rgb & 0xff) * scale)));
//				synchronized(output) {
//					output.setRGB(x, y, (r << 16) | (g << 8) | b);
//				}
//			});
//		});
//		return output;
//	}
//}