/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
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

package io.crate.metadata.table;

import io.crate.metadata.RelationInfo;
import io.crate.metadata.RelationName;

import java.util.ArrayList;
import java.util.List;

/**
 * This class is used as information store of table constraints when
 * being displayed.
 */
public class ConstraintInfo {

    /**
     * Enum that contains type of constraints (that are currently supported
     * by CrateDB)
     *
     * PRIMARY KEY is the primary key constraint of a table.
     * CHECK       is only used for NOT NULL constraints.
     */
    public enum Type {
        PRIMARY_KEY("PRIMARY KEY", "p"),
        CHECK("CHECK", "c");

        private final String text;
        private final String postgresChar;

        Type(final String text, String postgresChar) {
            this.text = text;
            this.postgresChar = postgresChar;
        }

        @Override
        public String toString() {
            return text;
        }

        public String postgresChar() {
            return postgresChar;
        }
    }

    private final String constraintName;
    private final List<Short> conkey;
    private final RelationInfo relationInfo;
    private final Type constraintType;

    public ConstraintInfo(RelationInfo relationInfo, String constraintName, Type constraintType) {
        this.relationInfo = relationInfo;
        this.constraintName = constraintName;
        this.constraintType = constraintType;
        this.conkey = getConstraintColumnIndices(relationInfo, constraintType);

    }

    private static List<Short> getConstraintColumnIndices(RelationInfo relationInfo, Type constraintType) {
        List<Short> result = new ArrayList<>();
        if (constraintType == Type.PRIMARY_KEY) {
            var pkeys = relationInfo.primaryKey();
            for (int i = 0; i < pkeys.size(); i++) {
                var iterator = relationInfo.columns().iterator();
                while (iterator.hasNext()) {
                    var ref = iterator.next();
                    if (ref.column().equals(pkeys.get(i))) {
                        result.add((short) ref.position());
                        break;
                    }
                }
            }
        } else {
            // conkey array is currently populated only for PK.
            // To make the same for CHECK we need to introduce breaking change and enrich "check_constraints" meta map with column name to resolve position
            // (or better just enrich by position to avoid looping)
            result = null;
        }
        return result;
    }

    public RelationName relationName() {
        return relationInfo.ident();
    }

    public RelationInfo relationInfo() {
        return relationInfo;
    }

    public String constraintName() {
        return constraintName;
    }

    public Type constraintType() {
        return constraintType;
    }

    public List<Short> conkey() {
        return conkey;
    }
}
