/*-
 * #%L
 * QuST is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * QuST is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QuST.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.qust;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import org.apache.parquet.io.DelegatingSeekableInputStream;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;

/**
 * A Parquet input file on the local disk that does not go through Hadoop's file system classes.
 * <p>
 * {@code HadoopInputFile} reaches Hadoop's {@code UserGroupInformation}, which calls {@code Subject.getSubject}; Java 24
 * and later (the Java of QuPath 0.7 and later) throw an {@code UnsupportedOperationException} there.
 *
 * @author Chao Hui Huang
 */
final class LocalParquetFile implements InputFile {

	private final Path path;

	LocalParquetFile(Path path) {
		this.path = path;
	}

	@Override
	public long getLength() throws IOException {
		return path.toFile().length();
	}

	@Override
	public SeekableInputStream newStream() throws IOException {
		RandomAccessFile file = new RandomAccessFile(path.toFile(), "r");
		return new DelegatingSeekableInputStream(new java.io.InputStream() {
			@Override
			public int read() throws IOException {
				return file.read();
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				return file.read(b, off, len);
			}

			@Override
			public void close() throws IOException {
				file.close();
			}
		}) {
			@Override
			public long getPos() throws IOException {
				return file.getFilePointer();
			}

			@Override
			public void seek(long newPos) throws IOException {
				file.seek(newPos);
			}

			@Override
			public void readFully(byte[] bytes, int start, int len) throws IOException {
				file.readFully(bytes, start, len);
			}

			@Override
			public void readFully(ByteBuffer buf) throws IOException {
				byte[] tmp = new byte[buf.remaining()];
				file.readFully(tmp);
				buf.put(tmp);
			}
		};
	}
}
