/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.engine.framer;

import uk.co.real_logic.agrona.concurrent.IdleStrategy;

import java.util.List;

public final class QueryLibrariesCommand implements AdminCommand
{
    private volatile List<LibraryInfo> response;

    public void execute(final Framer framer)
    {
        framer.onQueryLibraries(this);
    }

    void success(final List<LibraryInfo> response)
    {
        this.response = response;
    }

    public List<LibraryInfo> awaitResponse(final IdleStrategy idleStrategy)
    {
        List<LibraryInfo> response;
        while ((response = this.response) == null)
        {
            idleStrategy.idle();
        }
        idleStrategy.reset();
        return response;
    }
}