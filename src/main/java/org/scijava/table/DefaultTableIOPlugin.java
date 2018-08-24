/*
 * #%L
 * ImageJ software for multidimensional image
 * 			processing and analysis.
 * %%
 * Copyright (C) 2017 - 2018 Board of Regents of the University of
 * 			Wisconsin-Madison and University of Konstanz.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package org.scijava.table;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.scijava.Priority;
import org.scijava.io.AbstractIOPlugin;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.DataHandleService;
import org.scijava.io.location.FileLocation;
import org.scijava.io.IOPlugin;
import org.scijava.io.location.Location;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.FileUtils;

/**
 * Plugin for reading/writing {@link GenericTable}s.
 * 
 * @author Leon Yang
 */
@SuppressWarnings("rawtypes")
@Plugin(type = IOPlugin.class, priority = Priority.LOW)
public class DefaultTableIOPlugin extends AbstractIOPlugin<Table> {

	@Parameter
	private DataHandleService dataHandleService;

	/** Reads the first row of the input file as column headers. */
	@Parameter(required = false)
	private boolean readColHeaders = true;

	/** Writes column headers to file if there exists at least one. */
	@Parameter(required = false)
	private boolean writeColHeaders = true;

	/** Reads the first column of the input file as row headers. */
	@Parameter(required = false)
	private boolean readRowHeaders = false;

	/** Writes row headers to file if there exists at least one. */
	@Parameter(required = false)
	private boolean writeRowHeaders = true;

	/** Regex pattern that separates cells in each row of the table. */
	@Parameter(required = false)
	private char separator = ',';

	/** End of line when writing to file. */
	@Parameter(required = false)
	private String eol = System.lineSeparator();

	/**
	 * Quote character used for escaping separator and empty strings. Use two
	 * consecutive quotes to escape one.
	 */
	@Parameter(required = false)
	private char quote = '"';

	/**
	 * Text that appears at the top left corner when both column and row headers
	 * present.
	 */
	@Parameter(required = false)
	private String cornerText = "\\";

	/**
	 * Lambda function that converts the string of a cell to an appropriate value.
	 */
	@Parameter(required = false)
	private Function<String, Object> parser = s -> s;

	/** Lambda function that convert the cell content to a string. */
	@Parameter(required = false)
	private Function<Object, String> formatter = o -> o.toString();

	// FIXME: The "txt" extension is extremely general and will conflict with
	// other plugins. Consider another way to check supportsOpen/Close.
	private static final Set<String> SUPPORTED_EXTENSIONS = Collections
		.unmodifiableSet(new HashSet<>(Arrays.asList("csv", "txt", "prn", "dif",
			"rtf")));

	@Override
	public Class<Table> getDataType() {
		return Table.class;
	}

	@Override
	public boolean supportsOpen(final String source) {
		final String ext = FileUtils.getExtension(source).toLowerCase();
		return SUPPORTED_EXTENSIONS.contains(ext);
	}

	@Override
	public boolean supportsSave(final String source) {
		return supportsOpen(source);
	}

	/**
	 * Process a given line into a list of tokens.
	 */
	private ArrayList<String> processRow(final String line) throws IOException {
		final ArrayList<String> row = new ArrayList<>();
		final StringBuilder sb = new StringBuilder();
		int idx = 0;
		int start = idx;
		while (idx < line.length()) {
			if (line.charAt(idx) == quote) {
				sb.append(line.substring(start, idx));
				boolean quoted = true;
				idx++;
				start = idx;
				// find quoted string
				while (idx < line.length()) {
					if (line.charAt(idx) == quote) {
						sb.append(line.substring(start, idx));
						if (idx + 1 < line.length() && line.charAt(idx + 1) == quote) {
							sb.append(quote);
							idx += 2;
							start = idx;
						}
						else {
							idx++;
							start = idx;
							quoted = false;
							break;
						}
					}
					else {
						idx++;
					}
				}
				if (quoted) {
					throw new IOException(String.format(
						"Unbalanced quote at position %d: %s", idx, line));
				}
			}
			else if (line.charAt(idx) == separator) {
				sb.append(line.substring(start, idx));
				row.add(sb.toString());
				sb.setLength(0);
				idx++;
				start = idx;
			}
			else {
				idx++;
			}
		}
		sb.append(line.substring(start, idx));
		row.add(sb.toString());
		return row;
	}

	@Override
	public GenericTable open(final String source) throws IOException {
		// FIXME Assumes FileLocation
		final Location sourceLocation = new FileLocation(source);
		final GenericTable table = new DefaultGenericTable();

		try (final DataHandle<? extends Location> handle = //
			dataHandleService.create(sourceLocation))
		{
			if (!handle.exists()) {
				throw new IOException("Cannot open source");
			}
			long length = handle.length();

			final byte[] buffer = new byte[(int) length];
			handle.read(buffer);

			final String text = new String(buffer);


			// split by any line delimiter
			final String[] lines = text.split("\\R");
			if (lines.length == 0) return table;
			// process first line to get number of cols
			{
				final ArrayList<String> tokens = processRow(lines[0]);
				if (readColHeaders) {
					final List<String> colHeaders;
					if (readRowHeaders) colHeaders = tokens.subList(1, tokens.size());
					else colHeaders = tokens;
					final String[] colHeadersArr = new String[colHeaders.size()];
					table.appendColumns(colHeaders.toArray(colHeadersArr));
				}
				else {
					final List<String> cols;
					if (readRowHeaders) {
						cols = tokens.subList(1, tokens.size());
						table.appendColumns(cols.size());
						table.appendRow(tokens.get(0));
					}
					else {
						cols = tokens;
						table.appendColumns(cols.size());
						table.appendRow();
					}
					for (int i = 0; i < cols.size(); i++) {
						table.set(i, 0, parser.apply(cols.get(i)));
					}
				}
			}
			for (int lineNum = 1; lineNum < lines.length; lineNum++) {
				final String line = lines[lineNum];
				final ArrayList<String> tokens = processRow(line);
				final List<String> cols;
				if (readRowHeaders) {
					cols = tokens.subList(1, tokens.size());
					table.appendRow(tokens.get(0));
				}
				else {
					cols = tokens;
					table.appendRow();
				}
				if (cols.size() != table.getColumnCount()) {
					throw new IOException("Line " + table.getRowCount() +
						" is not the same length as the first line.");
				}
				for (int i = 0; i < cols.size(); i++) {
					table.set(i, lineNum - 1, parser.apply(cols.get(i)));
				}
			}
		}
		return table;
	}

	@Override
	public void save(final Table table, final String destination)
		throws IOException
	{
		// FIXME Assumes FileLocation
		final Location dstLocation = new FileLocation(destination);

		try (final DataHandle<Location> handle = //
			dataHandleService.create(dstLocation))
		{
			final boolean writeRH = this.writeRowHeaders && //
				table.getRowCount() > 0 && //
				IntStream.range(0, table.getRowCount()).allMatch(row -> table
					.getRowHeader(row) != null);
			final boolean writeCH = this.writeColHeaders && //
				table.getColumnCount() > 0 && //
				IntStream.range(0, table.getColumnCount()).allMatch(col -> table
					.getColumnHeader(col) != null);

				final StringBuilder sb = new StringBuilder();
				// write column headers
				if (writeCH) {
					if (writeRH) {
						sb.append(tryQuote(cornerText));
						if (table.getColumnCount() > 0) {
							sb.append(separator);
							sb.append(tryQuote(table.getColumnHeader(0)));
						}
					}
					// avoid adding extra separator when there is 0 column
					else if (table.getColumnCount() > 0) {
						sb.append(tryQuote(table.getColumnHeader(0)));
					}
					for (int col = 1; col < table.getColumnCount(); col++) {
						sb.append(separator);
						sb.append(tryQuote(table.getColumnHeader(col)));
					}
					sb.append(eol);
					handle.writeBytes(sb.toString());
					sb.setLength(0);
				}
				// write each row
				for (int row = 0; row < table.getRowCount(); row++) {
					if (writeRH) {
						sb.append(tryQuote(table.getRowHeader(row)));
						if (table.getColumnCount() > 0) {
							sb.append(separator);
							sb.append(tryQuote(formatter.apply(table.get(0, row))));
						}
					}
					// avoid adding extra separator when there is 0 column
					else if (table.getColumnCount() > 0) {
						sb.append(tryQuote(formatter.apply(table.get(0, row))));
					}
					for (int col = 1; col < table.getColumnCount(); col++) {
						sb.append(separator);
						sb.append(tryQuote(formatter.apply(table.get(col, row))));
					}
					sb.append(eol);
					handle.writeBytes(sb.toString());
					sb.setLength(0);
				}
		}

	}

	/**
	 * Try to quote a string if:
	 * <li>it is null or empty</li>
	 * <li>it has quotes inside</li>
	 * <li>it has separators or EOL inside</li>
	 * 
	 * @param str string to quote
	 * @return string, possibly quoted
	 */
	private String tryQuote(final String str) {
		if (str == null || str.length() == 0) return "" + quote + quote;
		if (str.indexOf(quote) != -1) return quote + str.replace("" + quote, "" +
			quote + quote) + quote;
		if (str.indexOf(separator) != -1) return quote + str + quote;
		return str;
	}
}
