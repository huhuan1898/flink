/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.formats.csv;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.function.SerializableSupplier;
import org.apache.flink.util.jackson.JacksonMapperFactory;

import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.DeserializationFeature;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectWriter;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.dataformat.csv.CsvMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.dataformat.csv.CsvSchema;

import java.util.Arrays;
import java.util.Objects;

import static org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN;

/**
 * Serialization schema that serializes an object of Flink Table & SQL internal data structure into
 * a CSV bytes.
 *
 * <p>Serializes the input row into a {@link JsonNode} and converts it into <code>byte[]</code>.
 *
 * <p>Result <code>byte[]</code> messages can be deserialized using {@link
 * CsvRowDataDeserializationSchema}.
 */
@PublicEvolving
public final class CsvRowDataSerializationSchema implements SerializationSchema<RowData> {

    private static final long serialVersionUID = 1L;

    /** Logical row type describing the input CSV data. */
    private final RowType rowType;

    /** Runtime instance that performs the actual work. */
    private final RowDataToCsvConverters.RowDataToCsvConverter runtimeConverter;

    private final SerializableSupplier<CsvMapper> csvMapperSuppler;

    /** CsvMapper used to write {@link JsonNode} into bytes. */
    private transient CsvMapper csvMapper;

    /** Schema describing the input CSV data. */
    private final CsvSchema csvSchema;

    /** Object writer used to write rows. It is configured by {@link CsvSchema}. */
    private transient ObjectWriter objectWriter;

    /** Reusable object node. */
    private transient ObjectNode root;

    /** Reusable converter context. */
    private transient RowDataToCsvConverters.RowDataToCsvConverter
                    .RowDataToCsvFormatConverterContext
            converterContext;

    private CsvRowDataSerializationSchema(
            RowType rowType,
            CsvSchema csvSchema,
            SerializableSupplier<CsvMapper> csvMapperSupplier) {
        this.rowType = rowType;
        this.runtimeConverter = RowDataToCsvConverters.createRowConverter(rowType);
        this.csvSchema = csvSchema.withLineSeparator("");
        this.csvMapperSuppler = csvMapperSupplier;
    }

    @Override
    public void open(InitializationContext context) throws Exception {
        this.csvMapper = csvMapperSuppler.get();
        this.objectWriter = csvMapper.writer(this.csvSchema);
    }

    /** A builder for creating a {@link CsvRowDataSerializationSchema}. */
    @PublicEvolving
    public static class Builder {

        private final RowType rowType;
        private CsvSchema csvSchema;
        private boolean isScientificNotation;

        /**
         * Creates a {@link CsvRowDataSerializationSchema} expecting the given {@link RowType}.
         *
         * @param rowType logical row type used to create schema.
         */
        public Builder(RowType rowType) {
            Preconditions.checkNotNull(rowType, "Row type must not be null.");

            this.rowType = rowType;
            this.csvSchema = CsvRowSchemaConverter.convert(rowType);
        }

        public Builder setFieldDelimiter(char c) {
            this.csvSchema = this.csvSchema.rebuild().setColumnSeparator(c).build();
            return this;
        }

        public Builder setArrayElementDelimiter(String delimiter) {
            Preconditions.checkNotNull(delimiter, "Delimiter must not be null.");
            this.csvSchema = this.csvSchema.rebuild().setArrayElementSeparator(delimiter).build();
            return this;
        }

        public Builder disableQuoteCharacter() {
            this.csvSchema = this.csvSchema.rebuild().disableQuoteChar().build();
            return this;
        }

        public Builder setQuoteCharacter(char c) {
            this.csvSchema = this.csvSchema.rebuild().setQuoteChar(c).build();
            return this;
        }

        public Builder setEscapeCharacter(char c) {
            this.csvSchema = this.csvSchema.rebuild().setEscapeChar(c).build();
            return this;
        }

        public Builder setNullLiteral(String s) {
            this.csvSchema = this.csvSchema.rebuild().setNullValue(s).build();
            return this;
        }

        public void setWriteBigDecimalInScientificNotation(boolean isScientificNotation) {
            this.isScientificNotation = isScientificNotation;
        }

        public CsvRowDataSerializationSchema build() {
            // assign to local variable to avoid reference to non-serializable builder
            final boolean isScientificNotation = this.isScientificNotation;
            return new CsvRowDataSerializationSchema(
                    rowType,
                    csvSchema,
                    () -> {
                        final CsvMapper csvMapper = JacksonMapperFactory.createCsvMapper();

                        csvMapper.configure(WRITE_BIGDECIMAL_AS_PLAIN, !isScientificNotation);
                        csvMapper.configure(
                                DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);
                        csvMapper.configure(
                                JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
                        return csvMapper;
                    });
        }
    }

    @Override
    public byte[] serialize(RowData row) {
        if (root == null) {
            root = csvMapper.createObjectNode();
            converterContext =
                    new RowDataToCsvConverters.RowDataToCsvConverter
                            .RowDataToCsvFormatConverterContext(csvMapper, root);
        }
        try {
            runtimeConverter.convert(row, converterContext);
            return objectWriter.writeValueAsBytes(root);
        } catch (Throwable t) {
            throw new RuntimeException(String.format("Could not serialize row '%s'.", row), t);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || o.getClass() != this.getClass()) {
            return false;
        }
        if (this == o) {
            return true;
        }
        final CsvRowDataSerializationSchema that = (CsvRowDataSerializationSchema) o;
        final CsvSchema otherSchema = that.csvSchema;

        return rowType.equals(that.rowType)
                && csvSchema.getColumnSeparator() == otherSchema.getColumnSeparator()
                && Arrays.equals(csvSchema.getLineSeparator(), otherSchema.getLineSeparator())
                && csvSchema
                        .getArrayElementSeparator()
                        .equals(otherSchema.getArrayElementSeparator())
                && csvSchema.getQuoteChar() == otherSchema.getQuoteChar()
                && csvSchema.getEscapeChar() == otherSchema.getEscapeChar()
                && Arrays.equals(csvSchema.getNullValue(), otherSchema.getNullValue());
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                rowType,
                csvSchema.getColumnSeparator(),
                csvSchema.getLineSeparator(),
                csvSchema.getArrayElementSeparator(),
                csvSchema.getQuoteChar(),
                csvSchema.getEscapeChar(),
                csvSchema.getNullValue());
    }
}
