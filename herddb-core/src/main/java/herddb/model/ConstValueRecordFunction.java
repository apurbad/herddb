/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */

package herddb.model;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import herddb.utils.Bytes;

/**
 * A Constant value for the record
 *
 * @author enrico.olivelli
 */
@SuppressFBWarnings({"EI_EXPOSE_REP2", "EI_EXPOSE_REP"})
public class ConstValueRecordFunction extends RecordFunction {

    private final byte[] value;

    public ConstValueRecordFunction(byte[] value) {
        this.value = value;
    }

    public ConstValueRecordFunction(Bytes value) {
        this.value = value.to_array();
    }

    @Override
    public byte[] computeNewValue(Record previous, StatementEvaluationContext context, TableContext tableContext) {
        return value;
    }

}
