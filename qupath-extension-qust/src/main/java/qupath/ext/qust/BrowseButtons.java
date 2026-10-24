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

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Puts a <em>Browse...</em> button next to the text fields of the parameter dialogs that ask for a file or a folder.
 * <p>
 * QuPath's parameter dialog has no file or folder parameter: such a value is a text field. The dialog is an ordinary JavaFX window,
 * so after it has been shown this class looks for the label of the field (the label of every parameter points to its field), and
 * puts the field and a button together in the place of the field. Nothing in the plugins changes: the field is still the same field
 * and sets the same parameter. If a QuPath version builds its dialog in another way, no button appears and the field works as before.
 */
final class BrowseButtons {

	private static final Logger logger = LoggerFactory.getLogger(BrowseButtons.class);

	private static final String DECORATED = "qust.browse.button";

	/** prompt of a parameter (the text of its label) -> true for a folder, false for a file */
	private static final Map<String, Boolean> PROMPTS = new ConcurrentHashMap<>();

	private static boolean listening = false;

	private BrowseButtons() {}

	/**
	 * Registers the parameters that get a button, and starts to watch the dialogs.
	 * @param folderPrompts prompts of the parameters that take a folder
	 * @param filePrompts prompts of the parameters that take a file
	 */
	static synchronized void install(Collection<String> folderPrompts, Collection<String> filePrompts) {
		folderPrompts.forEach(p -> PROMPTS.put(p, true));
		filePrompts.forEach(p -> PROMPTS.put(p, false));
		if (listening)
			return;
		Platform.runLater(() -> {
			Window.getWindows().addListener((ListChangeListener<Window>) change -> {
				while (change.next()) {
					if (change.wasAdded())
						for (Window window : new ArrayList<>(change.getAddedSubList()))
							Platform.runLater(() -> decorate(window));
				}
			});
		});
		listening = true;
	}

	/** Adds the buttons to the fields of a window that has been shown (also called by the tests). */
	static int decorate(Window window) {
		try {
			if (window == null || window.getScene() == null)
				return 0;
			int count = 0;
			for (Node node : new ArrayList<>(window.getScene().getRoot().lookupAll(".label"))) {
				if (!(node instanceof Label label) || label.getText() == null)
					continue;
				Boolean folder = PROMPTS.get(label.getText());
				if (folder == null || !(label.getLabelFor() instanceof TextField field))
					continue;
				if (field.getProperties().containsKey(DECORATED) || !(field.getParent() instanceof GridPane grid))
					continue;
				addButton(grid, field, folder, window);
				count++;
			}
			return count;
		} catch (RuntimeException e) {
			logger.warn("Could not add the Browse buttons to a dialog: {}", e.toString());
			return 0;
		}
	}

	private static void addButton(GridPane grid, TextField field, boolean folder, Window owner) {
		// the position and the constraints of the field in the grid, before the box takes it over
		int index = grid.getChildren().indexOf(field);
		Integer column = GridPane.getColumnIndex(field), row = GridPane.getRowIndex(field);
		Integer columnSpan = GridPane.getColumnSpan(field), rowSpan = GridPane.getRowSpan(field);
		Priority hgrow = GridPane.getHgrow(field) == null ? Priority.ALWAYS : GridPane.getHgrow(field);
		grid.getChildren().remove(field);

		Button button = new Button("Browse...");
		button.setMinWidth(Region.USE_PREF_SIZE);
		button.setOnAction(e -> choose(folder, field, owner));
		HBox box = new HBox(4, field, button);
		box.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(field, Priority.ALWAYS);
		field.setMaxWidth(Double.MAX_VALUE);

		// the box takes the place of the field in the grid, with the same constraints
		GridPane.setConstraints(box, column == null ? 0 : column, row == null ? 0 : row, columnSpan == null ? 1 : columnSpan,
				rowSpan == null ? 1 : rowSpan, GridPane.getHalignment(field), GridPane.getValignment(field), hgrow, GridPane.getVgrow(field),
				GridPane.getMargin(field));
		GridPane.setFillWidth(box, Boolean.TRUE);
		grid.getChildren().add(Math.max(0, Math.min(index, grid.getChildren().size())), box);
		field.getProperties().put(DECORATED, box);
	}

	private static void choose(boolean folder, TextField field, Window owner) {
		File current = existing(field.getText());
		File chosen;
		if (folder) {
			DirectoryChooser chooser = new DirectoryChooser();
			chooser.setTitle("Select a folder");
			if (current != null)
				chooser.setInitialDirectory(current.isDirectory() ? current : current.getParentFile());
			chosen = chooser.showDialog(owner);
		} else {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Select a file");
			if (current != null) {
				File dir = current.isDirectory() ? current : current.getParentFile();
				if (dir != null && dir.isDirectory())
					chooser.setInitialDirectory(dir);
				if (current.isFile())
					chooser.setInitialFileName(current.getName());
			}
			chosen = chooser.showOpenDialog(owner);
		}
		if (chosen != null)
			field.setText(chosen.getAbsolutePath());
	}

	/** The file or folder of a text, or the closest folder above it that exists; null for no such thing. */
	private static File existing(String text) {
		if (text == null || text.isBlank())
			return null;
		File file = new File(text.strip());
		while (file != null && !file.exists())
			file = file.getParentFile();
		return file;
	}
}
