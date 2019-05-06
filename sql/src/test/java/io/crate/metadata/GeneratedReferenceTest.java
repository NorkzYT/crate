/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.metadata;

import com.google.common.collect.ImmutableList;
import io.crate.analyze.relations.AnalyzedRelation;
import io.crate.analyze.relations.DocTableRelation;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.sql.tree.ColumnPolicy;
import io.crate.sql.tree.QualifiedName;
import io.crate.test.integration.CrateDummyClusterServiceUnitTest;
import io.crate.testing.SQLExecutor;
import io.crate.testing.SqlExpressions;
import io.crate.testing.T3;
import io.crate.types.StringType;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static io.crate.testing.T3.T1_DEFINITION;
import static io.crate.testing.T3.T1_RN;
import static org.hamcrest.Matchers.is;

public class GeneratedReferenceTest extends CrateDummyClusterServiceUnitTest {

    private Map<QualifiedName, AnalyzedRelation> sources;
    private SqlExpressions expressions;
    private DocTableInfo t1Info;

    @Before
    public void prepare() throws Exception {
        sources = T3.sources(List.of(T3.T1_RN), clusterService);
        t1Info = SQLExecutor.tableInfo(T1_RN, T1_DEFINITION, clusterService);

        DocTableRelation tr1 = (DocTableRelation) T3.fromSource(T3.T1_RN, sources);
        expressions = new SqlExpressions(sources, tr1);
    }

    @Test
    public void testStreaming() throws Exception {
        ReferenceIdent referenceIdent = new ReferenceIdent(t1Info.ident(), "generated_column");
        String formattedGeneratedExpression = "concat(a, 'bar')";
        GeneratedReference generatedReferenceInfo = new GeneratedReference(null,
            referenceIdent,
            RowGranularity.DOC,
            StringType.INSTANCE, ColumnPolicy.STRICT, Reference.IndexType.ANALYZED,
            formattedGeneratedExpression, false);

        generatedReferenceInfo.generatedExpression(expressions.normalize(expressions.asSymbol(formattedGeneratedExpression)));
        generatedReferenceInfo.referencedReferences(ImmutableList.of(t1Info.getReference(new ColumnIdent("a"))));

        BytesStreamOutput out = new BytesStreamOutput();
        Reference.toStream(generatedReferenceInfo, out);

        StreamInput in = out.bytes().streamInput();
        GeneratedReference generatedReferenceInfo2 = Reference.fromStream(in);

        assertThat(generatedReferenceInfo2, is(generatedReferenceInfo));
    }
}
