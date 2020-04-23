package com.fairphone.psensor;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * <p>
 * Copyright 2016 Fairphone B.V.
 */
public class UpdateFinalizerActivityFromNotification extends UpdateFinalizerActivity {
    @Override
    protected boolean isWizard() {
        return false;
    }

    @NonNull
    public static Intent getIntent(@NonNull Context context) {
        Intent intent = new Intent(context, UpdateFinalizerActivityFromNotification.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
