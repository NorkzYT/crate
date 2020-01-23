/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.metadata.doc;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import io.crate.Constants;
import io.crate.analyze.NumberOfReplicas;
import io.crate.analyze.ParamTypeHints;
import io.crate.analyze.TableParameters;
import io.crate.analyze.expressions.ExpressionAnalysisContext;
import io.crate.analyze.expressions.ExpressionAnalyzer;
import io.crate.analyze.expressions.TableReferenceResolver;
import io.crate.analyze.relations.FieldProvider;
import io.crate.common.collections.Lists2;
import io.crate.common.collections.Maps;
import io.crate.expression.symbol.Symbol;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.CoordinatorTxnCtx;
import io.crate.metadata.Functions;
import io.crate.metadata.GeneratedReference;
import io.crate.metadata.GeoReference;
import io.crate.metadata.IndexReference;
import io.crate.metadata.Reference;
import io.crate.metadata.ReferenceIdent;
import io.crate.metadata.RelationName;
import io.crate.metadata.RowGranularity;
import io.crate.metadata.table.ColumnPolicies;
import io.crate.metadata.table.Operation;
import io.crate.sql.parser.SqlParser;
import io.crate.sql.tree.ColumnPolicy;
import io.crate.sql.tree.Expression;
import io.crate.types.ArrayType;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import io.crate.types.ObjectType;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.Booleans;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.mapper.DateFieldMapper;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.index.mapper.TypeParsers.DOC_VALUES;

public class DocIndexMetaData {

    private static final String ID = "_id";

    private static final String SETTING_CLOSED = "closed";

    private final Map<String, Object> mappingMap;
    private final Map<ColumnIdent, IndexReference.Builder> indicesBuilder = new HashMap<>();

    private final Comparator<Reference> sortByPositionThenName = Comparator
        .comparing((Reference r) -> MoreObjects.firstNonNull(r.position(), 0))
        .thenComparing(o -> o.column().fqn());
    private final ImmutableSortedSet.Builder<Reference> columnsBuilder = ImmutableSortedSet.orderedBy(sortByPositionThenName);

    private final List<Reference> nestedColumns = new ArrayList<>();
    private final ImmutableList.Builder<GeneratedReference> generatedColumnReferencesBuilder = ImmutableList.builder();

    private final Functions functions;
    private final RelationName ident;
    private final int numberOfShards;
    private final String numberOfReplicas;
    private final Map<String, Object> tableParameters;
    private final Map<String, Object> indicesMap;
    private final ImmutableList<ColumnIdent> partitionedBy;
    private final Set<Operation> supportedOperations;
    private Collection<Reference> columns;
    private ImmutableMap<ColumnIdent, IndexReference> indices;
    private List<Reference> partitionedByColumns;
    private ImmutableList<GeneratedReference> generatedColumnReferences;
    private Map<ColumnIdent, Reference> references;
    private ImmutableList<ColumnIdent> primaryKey;
    private ImmutableCollection<ColumnIdent> notNullColumns;
    private ColumnIdent routingCol;
    private boolean hasAutoGeneratedPrimaryKey = false;
    private boolean closed;

    private ColumnPolicy columnPolicy = ColumnPolicy.STRICT;
    private Map<String, String> generatedColumns;

    @Nullable
    private final Version versionCreated;
    @Nullable
    private final Version versionUpgraded;

    /**
     * Analyzer used for Column Default expressions
     */
    private final ExpressionAnalyzer expressionAnalyzer;

    DocIndexMetaData(Functions functions, IndexMetaData metaData, RelationName ident) throws IOException {
        this.functions = functions;
        this.ident = ident;
        this.numberOfShards = metaData.getNumberOfShards();
        Settings settings = metaData.getSettings();
        this.numberOfReplicas = NumberOfReplicas.fromSettings(settings);
        this.mappingMap = getMappingMap(metaData);
        this.tableParameters = TableParameters.tableParametersFromIndexMetaData(metaData);

        Map<String, Object> metaMap = Maps.get(mappingMap, "_meta");
        indicesMap = Maps.getOrDefault(metaMap, "indices", ImmutableMap.of());
        List<List<String>> partitionedByList = Maps.getOrDefault(metaMap, "partitioned_by", List.of());
        this.partitionedBy = getPartitionedBy(partitionedByList);
        generatedColumns = Maps.getOrDefault(metaMap, "generated_columns", ImmutableMap.of());
        IndexMetaData.State state = isClosed(metaData, mappingMap, !partitionedByList.isEmpty()) ?
            IndexMetaData.State.CLOSE : IndexMetaData.State.OPEN;
        supportedOperations = Operation.buildFromIndexSettingsAndState(metaData.getSettings(), state);
        versionCreated = IndexMetaData.SETTING_INDEX_VERSION_CREATED.get(settings);
        versionUpgraded = settings.getAsVersion(IndexMetaData.SETTING_VERSION_UPGRADED, null);
        closed = state == IndexMetaData.State.CLOSE;

        this.expressionAnalyzer = new ExpressionAnalyzer(
            functions,
            CoordinatorTxnCtx.systemTransactionContext(),
            ParamTypeHints.EMPTY,
            FieldProvider.UNSUPPORTED,
            null);
    }

    private static Map<String, Object> getMappingMap(IndexMetaData metaData) {
        MappingMetaData mappingMetaData = metaData.mappingOrDefault(Constants.DEFAULT_MAPPING_TYPE);
        if (mappingMetaData == null) {
            return ImmutableMap.of();
        }
        return mappingMetaData.sourceAsMap();
    }

    public static boolean isClosed(IndexMetaData indexMetaData, Map<String, Object> mappingMap, boolean isPartitioned) {
        // Checking here for whether the closed flag exists on the template metadata, as partitioned tables that are
        // empty (and thus have no indexes who have states) need a way to set their state.
        if (isPartitioned) {
            return Maps.getOrDefault(
                Maps.getOrDefault(mappingMap, "_meta", null),
                SETTING_CLOSED,
                false);
        }
        return indexMetaData.getState() == IndexMetaData.State.CLOSE;
    }

    private void add(Integer position,
                     ColumnIdent column,
                     DataType type,
                     @Nullable String defaultExpression,
                     ColumnPolicy columnPolicy,
                     Reference.IndexType indexType,
                     boolean isNotNull,
                     boolean columnStoreEnabled) {
        Reference ref;
        boolean partitionByColumn = partitionedBy.contains(column);
        String generatedExpression = generatedColumns.get(column.fqn());
        if (partitionByColumn) {
            indexType = Reference.IndexType.NOT_ANALYZED;
        }
        if (generatedExpression == null) {
            ref = newInfo(position, column, type, defaultExpression, columnPolicy, indexType, isNotNull, columnStoreEnabled);
        } else {
            ref = newGeneratedColumnInfo(position, column, type, columnPolicy, indexType, generatedExpression, isNotNull);
        }
        if (column.isTopLevel()) {
            columnsBuilder.add(ref);
        } else {
            nestedColumns.add(ref);
        }
        if (ref instanceof GeneratedReference) {
            generatedColumnReferencesBuilder.add((GeneratedReference) ref);
        }
    }

    private void addGeoReference(Integer position,
                                 ColumnIdent column,
                                 @Nullable String tree,
                                 @Nullable String precision,
                                 @Nullable Integer treeLevels,
                                 @Nullable Double distanceErrorPct) {
        GeoReference info = new GeoReference(
            position,
            refIdent(column),
            tree,
            precision,
            treeLevels,
            distanceErrorPct);
        if (column.isTopLevel()) {
            columnsBuilder.add(info);
        } else {
            nestedColumns.add(info);
        }
    }

    private ReferenceIdent refIdent(ColumnIdent column) {
        return new ReferenceIdent(ident, column);
    }

    private GeneratedReference newGeneratedColumnInfo(Integer position,
                                                      ColumnIdent column,
                                                      DataType type,
                                                      ColumnPolicy columnPolicy,
                                                      Reference.IndexType indexType,
                                                      String generatedExpression,
                                                      boolean isNotNull) {
        return new GeneratedReference(
            position,
            refIdent(column),
            granularity(column),
            type,
            columnPolicy,
            indexType,
            generatedExpression,
            isNotNull);
    }

    private RowGranularity granularity(ColumnIdent column) {
        if (partitionedBy.contains(column)) {
            return RowGranularity.PARTITION;
        }
        return RowGranularity.DOC;
    }

    private Reference newInfo(Integer position,
                              ColumnIdent column,
                              DataType type,
                              @Nullable String formattedDefaultExpression,
                              ColumnPolicy columnPolicy,
                              Reference.IndexType indexType,
                              boolean nullable,
                              boolean columnStoreEnabled) {
        Symbol defaultExpression = null;
        if (formattedDefaultExpression != null) {
            Expression expression = SqlParser.createExpression(formattedDefaultExpression);
            defaultExpression = expressionAnalyzer.convert(expression, new ExpressionAnalysisContext());
        }
        return new Reference(
            refIdent(column),
            granularity(column),
            type,
            columnPolicy,
            indexType,
            nullable,
            columnStoreEnabled,
            position,
            defaultExpression
        );
    }

    /**
     * extract dataType from given columnProperties
     *
     * @param columnProperties map of String to Object containing column properties
     * @return dataType of the column with columnProperties
     */
    static DataType getColumnDataType(Map<String, Object> columnProperties) {
        DataType type;
        String typeName = (String) columnProperties.get("type");

        if (typeName == null || ObjectType.NAME.equals(typeName)) {
            Map<String, Object> innerProperties = (Map<String, Object>) columnProperties.get("properties");
            if (innerProperties != null) {
                ObjectType.Builder builder = ObjectType.builder();
                for (Map.Entry<String, Object> entry : innerProperties.entrySet()) {
                    builder.setInnerType(entry.getKey(), getColumnDataType((Map<String, Object>) entry.getValue()));
                }
                type = builder.build();
            } else {
                type = MoreObjects.firstNonNull(DataTypes.ofMappingName(typeName), DataTypes.NOT_SUPPORTED);
            }
        } else if (typeName.equalsIgnoreCase("array")) {

            Map<String, Object> innerProperties = Maps.get(columnProperties, "inner");
            DataType innerType = getColumnDataType(innerProperties);
            type = new ArrayType(innerType);
        } else {
            typeName = typeName.toLowerCase(Locale.ENGLISH);
            if (DateFieldMapper.CONTENT_TYPE.equals(typeName)) {
                Boolean ignoreTimezone = (Boolean) columnProperties.get("ignore_timezone");
                if (ignoreTimezone != null && ignoreTimezone) {
                    return DataTypes.TIMESTAMP;
                } else {
                    return DataTypes.TIMESTAMPZ;
                }
            }
            type = MoreObjects.firstNonNull(DataTypes.ofMappingName(typeName), DataTypes.NOT_SUPPORTED);
        }
        return type;
    }

    /**
     * Get the IndexType from columnProperties.
     * <br />
     * Properties might look like:
     * <pre>
     *     {
     *         "type": "integer"
     *     }
     *
     *
     *     {
     *         "type": "text",
     *         "analyzer": "english"
     *     }
     *
     *
     *     {
     *          "type": "text",
     *          "fields": {
     *              "keyword": {
     *                  "type": "keyword",
     *                  "ignore_above": "256"
     *              }
     *          }
     *     }
     *
     *     {
     *         "type": "date",
     *         "index": "no"
     *     }
     *
     *     {
     *          "type": "keyword",
     *          "index": false
     *     }
     * </pre>
     */
    private static Reference.IndexType getColumnIndexType(Map<String, Object> columnProperties) {
        Object index = columnProperties.get("index");
        if (index == null) {
            if ("text".equals(columnProperties.get("type"))) {
                return Reference.IndexType.ANALYZED;
            }
            return Reference.IndexType.NOT_ANALYZED;
        }
        if (Boolean.FALSE.equals(index) || "no".equals(index) || "false".equals(index)) {
            return Reference.IndexType.NO;
        }

        if ("not_analyzed".equals(index)) {
            return Reference.IndexType.NOT_ANALYZED;
        }
        return Reference.IndexType.ANALYZED;
    }

    private static ColumnIdent childIdent(@Nullable ColumnIdent ident, String name) {
        if (ident == null) {
            return new ColumnIdent(name);
        }
        return ColumnIdent.getChild(ident, name);
    }

    /**
     * extracts index definitions as well
     */
    @SuppressWarnings("unchecked")
    private void internalExtractColumnDefinitions(@Nullable ColumnIdent columnIdent,
                                                  @Nullable Map<String, Object> propertiesMap) {
        if (propertiesMap == null) {
            return;
        }
        for (Map.Entry<String, Object> columnEntry : propertiesMap.entrySet()) {
            Map<String, Object> columnProperties = (Map) columnEntry.getValue();
            DataType columnDataType = getColumnDataType(columnProperties);
            ColumnIdent newIdent = childIdent(columnIdent, columnEntry.getKey());


            boolean nullable = !notNullColumns.contains(newIdent);
            columnProperties = furtherColumnProperties(columnProperties);
            Integer position = (Integer) columnProperties.getOrDefault("position", null);
            String defaultExpression = (String) columnProperties.getOrDefault("default_expr", null);
            Reference.IndexType columnIndexType = getColumnIndexType(columnProperties);
            boolean columnsStoreDisabled = !Booleans.parseBoolean(
                columnProperties.getOrDefault(DOC_VALUES, true).toString());
            if (columnDataType == DataTypes.GEO_SHAPE) {
                String geoTree = (String) columnProperties.get("tree");
                String precision = (String) columnProperties.get("precision");
                Integer treeLevels = (Integer) columnProperties.get("tree_levels");
                Double distanceErrorPct = (Double) columnProperties.get("distance_error_pct");
                addGeoReference(position, newIdent, geoTree, precision, treeLevels, distanceErrorPct);
            } else if (columnDataType.id() == ObjectType.ID
                       || (columnDataType.id() == ArrayType.ID
                           && ((ArrayType) columnDataType).innerType().id() == ObjectType.ID)) {
                ColumnPolicy columnPolicy = ColumnPolicies.decodeMappingValue(columnProperties.get("dynamic"));
                add(position, newIdent, columnDataType, defaultExpression, columnPolicy, Reference.IndexType.NO, nullable, false);

                if (columnProperties.get("properties") != null) {
                    // walk nested
                    internalExtractColumnDefinitions(newIdent, (Map<String, Object>) columnProperties.get("properties"));
                }
            } else if (columnDataType != DataTypes.NOT_SUPPORTED) {
                List<String> copyToColumns = Maps.get(columnProperties, "copy_to");

                // extract columns this column is copied to, needed for indices
                if (copyToColumns != null) {
                    for (String copyToColumn : copyToColumns) {
                        ColumnIdent targetIdent = ColumnIdent.fromPath(copyToColumn);
                        IndexReference.Builder builder = getOrCreateIndexBuilder(targetIdent);
                        builder.addColumn(newInfo(position, newIdent, columnDataType, defaultExpression, ColumnPolicy.DYNAMIC, columnIndexType, false, columnsStoreDisabled));
                    }
                }
                // is it an index?
                if (indicesMap.containsKey(newIdent.fqn())) {
                    IndexReference.Builder builder = getOrCreateIndexBuilder(newIdent);
                    builder.indexType(columnIndexType)
                        .analyzer((String) columnProperties.get("analyzer"));
                } else {
                    add(position, newIdent, columnDataType, defaultExpression, ColumnPolicy.DYNAMIC, columnIndexType, nullable, columnsStoreDisabled);
                }
            }
        }
    }

    /**
     * get the real column properties from a possible array mapping,
     * keeping most of this stuff inside "inner"
     */
    private Map<String, Object> furtherColumnProperties(Map<String, Object> columnProperties) {
        if (columnProperties.get("inner") != null) {
            return (Map<String, Object>) columnProperties.get("inner");
        } else {
            return columnProperties;
        }
    }

    private IndexReference.Builder getOrCreateIndexBuilder(ColumnIdent ident) {
        return indicesBuilder.computeIfAbsent(ident, k -> new IndexReference.Builder(refIdent(ident)));
    }

    private ImmutableList<ColumnIdent> getPrimaryKey() {
        Map<String, Object> metaMap = Maps.get(mappingMap, "_meta");
        if (metaMap != null) {
            ImmutableList.Builder<ColumnIdent> builder = ImmutableList.builder();
            Object pKeys = metaMap.get("primary_keys");
            if (pKeys != null) {
                if (pKeys instanceof String) {
                    builder.add(ColumnIdent.fromPath((String) pKeys));
                    return builder.build();
                } else if (pKeys instanceof Collection) {
                    Collection keys = (Collection) pKeys;
                    if (!keys.isEmpty()) {
                        for (Object pkey : keys) {
                            builder.add(ColumnIdent.fromPath(pkey.toString()));
                        }
                        return builder.build();
                    }
                }
            }
        }
        if (getCustomRoutingCol() == null && partitionedBy.isEmpty()) {
            hasAutoGeneratedPrimaryKey = true;
            return ImmutableList.of(DocSysColumns.ID);
        }
        return ImmutableList.of();
    }

    private ImmutableCollection<ColumnIdent> getNotNullColumns() {
        Map<String, Object> metaMap = Maps.get(mappingMap, "_meta");
        if (metaMap != null) {
            ImmutableSet.Builder<ColumnIdent> builder = ImmutableSet.builder();
            Map<String, Object> constraintsMap = Maps.get(metaMap, "constraints");
            if (constraintsMap != null) {
                Object notNullColumnsMeta = constraintsMap.get("not_null");
                if (notNullColumnsMeta != null) {
                    Collection notNullColumns = (Collection) notNullColumnsMeta;
                    if (!notNullColumns.isEmpty()) {
                        for (Object notNullColumn : notNullColumns) {
                            builder.add(ColumnIdent.fromPath(notNullColumn.toString()));
                        }
                        return builder.build();
                    }
                }
            }
        }
        return ImmutableList.of();
    }

    private static ImmutableList<ColumnIdent> getPartitionedBy(List<List<String>> partitionedByList) {
        ImmutableList.Builder<ColumnIdent> builder = ImmutableList.builder();
        for (List<String> partitionedByInfo : partitionedByList) {
            builder.add(ColumnIdent.fromPath(partitionedByInfo.get(0)));
        }
        return builder.build();
    }

    private ColumnPolicy getColumnPolicy() {
        return ColumnPolicies.decodeMappingValue(mappingMap.get("dynamic"));
    }

    private void createColumnDefinitions() {
        Map<String, Object> propertiesMap = Maps.get(mappingMap, "properties");
        internalExtractColumnDefinitions(null, propertiesMap);
    }

    private ImmutableMap<ColumnIdent, IndexReference> createIndexDefinitions() {
        ImmutableMap.Builder<ColumnIdent, IndexReference> builder = ImmutableMap.builder();
        for (Map.Entry<ColumnIdent, IndexReference.Builder> entry : indicesBuilder.entrySet()) {
            builder.put(entry.getKey(), entry.getValue().build());
        }
        indices = builder.build();
        return indices;
    }

    private ColumnIdent getCustomRoutingCol() {
        if (mappingMap != null) {
            Map<String, Object> metaMap = Maps.get(mappingMap, "_meta");
            if (metaMap != null) {
                String routingPath = (String) metaMap.get("routing");
                if (routingPath != null && !routingPath.equals(ID)) {
                    return ColumnIdent.fromPath(routingPath);
                }
            }
        }
        return null;
    }

    private ColumnIdent getRoutingCol() {
        ColumnIdent col = getCustomRoutingCol();
        if (col != null) {
            return col;
        }
        if (primaryKey.size() == 1) {
            return primaryKey.get(0);
        }
        return DocSysColumns.ID;
    }

    private void initializeGeneratedExpressions() {
        if (generatedColumnReferences.isEmpty()) {
            return;
        }
        Collection<Reference> references = this.references.values();
        TableReferenceResolver tableReferenceResolver = new TableReferenceResolver(references, ident);
        ExpressionAnalyzer expressionAnalyzer = new ExpressionAnalyzer(
            functions, CoordinatorTxnCtx.systemTransactionContext(), ParamTypeHints.EMPTY, tableReferenceResolver, null);
        ExpressionAnalysisContext context = new ExpressionAnalysisContext();
        for (Reference reference : generatedColumnReferences) {
            GeneratedReference generatedReference = (GeneratedReference) reference;
            Expression expression = SqlParser.createExpression(generatedReference.formattedGeneratedExpression());
            generatedReference.generatedExpression(expressionAnalyzer.convert(expression, context));
            generatedReference.referencedReferences(ImmutableList.copyOf(tableReferenceResolver.references()));
            tableReferenceResolver.references().clear();
        }
    }

    public DocIndexMetaData build() {
        notNullColumns = getNotNullColumns();
        columnPolicy = getColumnPolicy();
        createColumnDefinitions();
        indices = createIndexDefinitions();
        columns = columnsBuilder.build();
        references = new LinkedHashMap<>();
        DocSysColumns.forTable(ident, references::put);
        nestedColumns.sort(sortByPositionThenName);
        for (Reference ref : columns) {
            references.put(ref.column(), ref);
            for (Reference nestedColumn : nestedColumns) {
                if (nestedColumn.column().getRoot().equals(ref.column())) {
                    references.put(nestedColumn.column(), nestedColumn);
                }
            }
        }
        // Order of the partitionedByColumns is important; Must be the same order as `partitionedBy` is in.
        partitionedByColumns = Lists2.map(partitionedBy, references::get);
        generatedColumnReferences = generatedColumnReferencesBuilder.build();
        primaryKey = getPrimaryKey();
        routingCol = getRoutingCol();

        initializeGeneratedExpressions();
        return this;
    }

    public Map<ColumnIdent, Reference> references() {
        return references;
    }

    public Collection<Reference> columns() {
        return columns;
    }

    public ImmutableMap<ColumnIdent, IndexReference> indices() {
        return indices;
    }

    public List<Reference> partitionedByColumns() {
        return partitionedByColumns;
    }

    ImmutableList<GeneratedReference> generatedColumnReferences() {
        return generatedColumnReferences;
    }

    ImmutableCollection<ColumnIdent> notNullColumns() {
        return notNullColumns;
    }

    public ImmutableList<ColumnIdent> primaryKey() {
        return primaryKey;
    }

    ColumnIdent routingCol() {
        return routingCol;
    }

    boolean hasAutoGeneratedPrimaryKey() {
        return hasAutoGeneratedPrimaryKey;
    }

    public int numberOfShards() {
        return numberOfShards;
    }

    public String numberOfReplicas() {
        return numberOfReplicas;
    }

    public ImmutableList<ColumnIdent> partitionedBy() {
        return partitionedBy;
    }

    public ColumnPolicy columnPolicy() {
        return columnPolicy;
    }

    public Map<String, Object> tableParameters() {
        return tableParameters;
    }

    private ImmutableMap<ColumnIdent, String> getAnalyzers(ColumnIdent columnIdent, Map<String, Object> propertiesMap) {
        ImmutableMap.Builder<ColumnIdent, String> builder = ImmutableMap.builder();
        for (Map.Entry<String, Object> columnEntry : propertiesMap.entrySet()) {
            Map<String, Object> columnProperties = (Map) columnEntry.getValue();
            DataType columnDataType = getColumnDataType(columnProperties);
            ColumnIdent newIdent = childIdent(columnIdent, columnEntry.getKey());
            columnProperties = furtherColumnProperties(columnProperties);
            if (columnDataType.id() == ObjectType.ID
                || (columnDataType.id() == ArrayType.ID
                    && ((ArrayType) columnDataType).innerType().id() == ObjectType.ID)) {
                if (columnProperties.get("properties") != null) {
                    builder.putAll(getAnalyzers(newIdent, (Map<String, Object>) columnProperties.get("properties")));
                }
            }
            String analyzer = (String) columnProperties.get("analyzer");
            if (analyzer != null) {
                builder.put(newIdent, analyzer);
            }
        }
        return builder.build();
    }

    ImmutableMap<ColumnIdent, String> analyzers() {
        Map<String, Object> propertiesMap = Maps.get(mappingMap, "properties");
        if (propertiesMap == null) {
            return ImmutableMap.of();
        } else {
            return getAnalyzers(null, propertiesMap);
        }
    }

    Set<Operation> supportedOperations() {
        return supportedOperations;
    }

    @Nullable
    public Version versionCreated() {
        return versionCreated;
    }

    @Nullable
    public Version versionUpgraded() {
        return versionUpgraded;
    }

    public boolean isClosed() {
        return closed;
    }
}
