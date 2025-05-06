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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.stat.correlation.Covariance;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.linear.ArrayRealVector;

public class MacenkoStainingNormalizer {
	public double[][] targetStainReferenceMatrix = {
           {0.65, 0.70, 0.29},   // Hematoxylin
           {0.07, 0.99, 0.11}    // Eosin
	};
	
	public double OD_threshold = 0.15;   // Tissue filtering threshold
	
	public BufferedImage normalizeToReferenceImage(BufferedImage inputImage, double[][] est_W, double[][] ref_W) {
		BufferedImage standardized = standardizeBrightness(inputImage);
		int width = standardized.getWidth();
		int height = standardized.getHeight();
		int N = width * height;

		double[][] odMatrix = new double[N][3];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int rgb = standardized.getRGB(x, y);
				int r = (rgb >> 16) & 0xff;
				int g = (rgb >> 8) & 0xff;
				int b = rgb & 0xff;
				odMatrix[y * width + x] = new double[] {
						-Math.log((r + 1.0) / 256.0),
						-Math.log((g + 1.0) / 256.0),
						-Math.log((b + 1.0) / 256.0)
				};
			}
		}
       
		double[][] concentrations = getConcentrations(odMatrix, est_W);
		double[] maxCSource = percentilePerColumn(concentrations, 99);
		double[] maxCTarget = percentilePerColumn(generateTargetConcentrations(N, ref_W), 99);
       
		for (int i = 0; i < N; i++) {
			for (int j = 0; j < 2; j++) {
				concentrations[i][j] *= (maxCTarget[j] / maxCSource[j]);
			}
		}
       
		RealMatrix C = MatrixUtils.createRealMatrix(concentrations);
		RealMatrix Wref = MatrixUtils.createRealMatrix(ref_W);
		RealMatrix ODNew = C.multiply(Wref);
		BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
       
		IntStream.range(0, N).parallel().forEach(i -> {
			double[] od = ODNew.getRow(i);
			int[] rgb = new int[3];
			for (int j = 0; j < 3; j++) {
				rgb[j] = Math.max(0, Math.min(255, (int) Math.round(255 * Math.exp(-od[j]))));
			}
			int x = i % width;
			int y = i / width;
			synchronized (result) {
				result.setRGB(x, y, (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]);
			}
		});
       
		return result;
	}
	
	public double[][] getStainMatrix(BufferedImage image, double beta, double alpha) {
		int width = image.getWidth();
		int height = image.getHeight();
		double[][][] rgbData = new double[height][width][3];
		IntStream.range(0, height).parallel().forEach(y -> {
			IntStream.range(0, width).parallel().forEach(x -> {
				int rgb = image.getRGB(x, y);
				rgbData[y][x][0] = (rgb >> 16) & 0xff;
				rgbData[y][x][1] = (rgb >> 8) & 0xff;
				rgbData[y][x][2] = rgb & 0xff;
			});
		});
       
		List<double[]> ODList = Collections.synchronizedList(new ArrayList<>());
		IntStream.range(0, height).parallel().forEach(y -> {
			IntStream.range(0, width).parallel().forEach(x -> {
				double[] rgb = rgbData[y][x];
				double[] od = new double[3];
				IntStream.range(0, 3).parallel().forEach(c -> {
					od[c] = -Math.log((rgb[c] + 1.0) / 256.0);
				});
				if (od[0] > beta || od[1] > beta || od[2] > beta) {
					ODList.add(od);
				}
			});
		});
		double[][] ODArray = ODList.toArray(new double[0][0]);
		RealMatrix OD = MatrixUtils.createRealMatrix(ODArray);
		Covariance cov = new Covariance(OD);
		RealMatrix covMatrix = cov.getCovarianceMatrix();
		EigenDecomposition eig = new EigenDecomposition(covMatrix);
		RealMatrix V = MatrixUtils.createRealMatrix(3, 2);
		double[] eigenVals = eig.getRealEigenvalues();
		Integer[] idx = {0, 1, 2};
		Arrays.sort(idx, Comparator.comparingDouble(i -> eigenVals[i]));
		int i1 = idx[2];
		int i2 = idx[1];
		RealVector v1 = eig.getEigenvector(i1);
		RealVector v2 = eig.getEigenvector(i2);
		if (v1.getEntry(0) < 0) v1 = v1.mapMultiply(-1);
		if (v2.getEntry(0) < 0) v2 = v2.mapMultiply(-1);
		V.setColumnVector(0, v1);
		V.setColumnVector(1, v2);
		RealMatrix That = OD.multiply(V);
		double[] phi = new double[That.getRowDimension()];
		IntStream.range(0, phi.length).parallel().forEach(i -> {
			double x = That.getEntry(i, 0);
			double y = That.getEntry(i, 1);
			phi[i] = Math.atan2(y, x);
		});
		Arrays.sort(phi);
		double minPhi = percentile(phi, alpha);
		double maxPhi = percentile(phi, 100 - alpha);
		double[] v1hat = new double[3];
		double[] v2hat = new double[3];
		IntStream.range(0, 3).parallel().forEach(i -> {
			v1hat[i] = V.getEntry(i, 0) * Math.cos(minPhi) + V.getEntry(i, 1) * Math.sin(minPhi);
			v2hat[i] = V.getEntry(i, 0) * Math.cos(maxPhi) + V.getEntry(i, 1) * Math.sin(maxPhi);
		});
		double[][] HE;
		if (v1hat[0] > v2hat[0]) {
			HE = new double[][]{normalize(v1hat), normalize(v2hat)};
		} else {
			HE = new double[][]{normalize(v2hat), normalize(v1hat)};
		}
		return HE;
	}
      
	   
	public BufferedImage concatBufferedImages(List<BufferedImage> images) {
		int totalHeight = images.stream().mapToInt(BufferedImage::getHeight).sum();
		int maxWidth = images.stream().mapToInt(BufferedImage::getWidth).max().orElse(0);
		BufferedImage result = new BufferedImage(maxWidth, totalHeight, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g2d = result.createGraphics();
		int currentY = 0;
		for (BufferedImage img : images) {
			g2d.drawImage(img, 0, currentY, null);
			currentY += img.getHeight();
		}
		g2d.dispose();
		return result;
	}
   
	private static double[][] getConcentrations(double[][] ODMatrix, double[][] stainMatrix) {
		int numPixels = ODMatrix.length;
		double[][] concentrations = new double[numPixels][2];
		RealMatrix D = MatrixUtils.createRealMatrix(stainMatrix).transpose();
		IntStream.range(0, numPixels).parallel().forEach(i -> {
			RealVector x = new ArrayRealVector(ODMatrix[i]);
			concentrations[i] = solveNNLS(D, x);
		});
		return concentrations;
	}
   
	private static double[] solveNNLS(RealMatrix D, RealVector x) {
		int maxIter = 100;
		double tol = 1e-4;
		double alpha = 1.0;
		RealVector c = new ArrayRealVector(D.getColumnDimension());
		for (int iter = 0; iter < maxIter; iter++) {
			RealVector grad = D.transpose().operate(D.operate(c).subtract(x));
			RealVector cNew = c.subtract(grad.mapMultiply(alpha));
			for (int i = 0; i < cNew.getDimension(); i++) {
				cNew.setEntry(i, Math.max(0, cNew.getEntry(i)));
			}
			if (c.getDistance(cNew) < tol) break;
			c = cNew;
		}
		return c.toArray();
	}
	

	private static double[] normalize(double[] v) {
		double norm = Math.sqrt(Arrays.stream(v).map(x -> x * x).sum());
		return Arrays.stream(v).map(x -> x / norm).toArray();
	}
	
	private static double percentile(double[] values, double p) {
		int index = (int) Math.round(p / 100.0 * (values.length - 1));
		return values[Math.max(0, Math.min(index, values.length - 1))];
	}
	
	private static double[] percentilePerColumn(double[][] matrix, double percentile) {
		int cols = matrix[0].length;
		double[] result = new double[cols];
		IntStream.range(0, cols).parallel().forEach(j -> {
			double[] column = new double[matrix.length];
			IntStream.range(0, matrix.length).parallel().forEach(i -> {
				column[i] = matrix[i][j];
			});
			Arrays.sort(column);
			int index = (int) Math.round((percentile / 100.0) * (column.length - 1));
			result[j] = column[Math.min(index, column.length - 1)];
		});
		return result;
	}
	private static double cosineSimilarity(double[] a, double[] b) {
		double dot = 0, normA = 0, normB = 0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
			normA += a[i] * a[i];
			normB += b[i] * b[i];
		}
		return dot / (Math.sqrt(normA) * Math.sqrt(normB));
	}
	
	double[][] reorderStainsByCosineSimilarity(double[][] estW, double[][] refW) {
		double sim0 = cosineSimilarity(estW[0], refW[0]);
		double sim1 = cosineSimilarity(estW[1], refW[0]);
		return sim0 >= sim1 ? estW : new double[][]{estW[1], estW[0]};
	}
	
	private static double[][] generateTargetConcentrations(int N, double[][] ref_W) {
		double[][] C = new double[N][2];
		for (int i = 0; i < N; i++) {
			C[i][0] = 1.0;
			C[i][1] = 1.0;
		}
		return C;
	}
	
	private static BufferedImage standardizeBrightness(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		int[] intensityValues = new int[width * height * 3];

		AtomicInteger index = new AtomicInteger(0);	
		IntStream.range(0, height).parallel().forEach(y -> {
			IntStream.range(0, width).parallel().forEach(x -> {
				int rgb = image.getRGB(x, y);
				intensityValues[index.getAndIncrement()] = (rgb >> 16) & 0xff;
				intensityValues[index.getAndIncrement()] = (rgb >> 8) & 0xff;
				intensityValues[index.getAndIncrement()] = rgb & 0xff;
				
			});
		});
		Arrays.sort(intensityValues);
		int p90Index = (int) (0.9 * intensityValues.length);
		double p90 = intensityValues[Math.min(p90Index, intensityValues.length - 1)];
		double scale = 255.0 / p90;
		BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		IntStream.range(0, height).parallel().forEach(y -> {
			IntStream.range(0, width).parallel().forEach(x -> {
				int rgb = image.getRGB(x, y);
				int r = Math.min(255, Math.max(0, (int) Math.round(((rgb >> 16) & 0xff) * scale)));
				int g = Math.min(255, Math.max(0, (int) Math.round(((rgb >> 8) & 0xff) * scale)));
				int b = Math.min(255, Math.max(0, (int) Math.round((rgb & 0xff) * scale)));
				synchronized(output) {
					output.setRGB(x, y, (r << 16) | (g << 8) | b);
				}
			});
		});
		return output;
	}
}