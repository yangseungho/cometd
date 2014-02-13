/*
 * Copyright (c) 2008-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.server.transport;

import java.io.IOException;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.List;
import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.util.Utf8StringBuilder;

public class AsyncJSONTransport extends AbstractHttpTransport
{
    private final static String PREFIX = "long-polling.json";
    private final static String NAME = "long-polling";

    public AsyncJSONTransport(BayeuxServerImpl bayeux)
    {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    @Override
    public boolean accept(HttpServletRequest request)
    {
        return "POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        String encoding = request.getCharacterEncoding();
        if (encoding == null)
            encoding = "UTF-8";
        request.setCharacterEncoding(encoding);
        AsyncContext asyncContext = request.startAsync(request, response);
        // Explicitly disable the timeout, to prevent
        // that the timeout fires in case of slow reads.
        asyncContext.setTimeout(0);
        Charset charset = Charset.forName(encoding);
        ReadListener reader = "UTF-8".equals(charset.name()) ? new UTF8Reader(asyncContext) : new CharsetReader(asyncContext, charset);
        ServletInputStream input = request.getInputStream();
        input.setReadListener(reader);
    }

    protected HttpScheduler suspend(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, ServerMessage.Mutable reply, String browserId, long timeout)
    {
        AsyncContext asyncContext = request.getAsyncContext();
        return newHttpScheduler(asyncContext, session, reply, browserId, timeout);
    }

    protected HttpScheduler newHttpScheduler(AsyncContext asyncContext, ServerSessionImpl session, ServerMessage.Mutable reply, String browserId, long timeout)
    {
        return new AsyncLongPollScheduler(asyncContext, session, reply, browserId, timeout);
    }

    @Override
    protected void write(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, boolean startInterval, List<ServerMessage> messages, ServerMessage.Mutable[] replies)
    {
        AsyncContext asyncContext = request.getAsyncContext();
        try
        {
            // Always write asynchronously
            response.setContentType("application/json;charset=UTF-8");
            ServletOutputStream output = response.getOutputStream();
            output.setWriteListener(new Writer(asyncContext, session, startInterval, messages, replies));
        }
        catch (Exception x)
        {
            _logger.debug("Exception while writing messages", x);
            error(asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private void error(AsyncContext asyncContext, int responseCode)
    {
        error(asyncContext, (HttpServletResponse)asyncContext.getResponse(), responseCode);
    }

    protected abstract class AbstractReader implements ReadListener
    {
        protected static final int CAPACITY = 512;

        private final byte[] buffer = new byte[CAPACITY];
        protected final AsyncContext asyncContext;

        protected AbstractReader(AsyncContext asyncContext)
        {
            this.asyncContext = asyncContext;
        }

        @Override
        public void onDataAvailable() throws IOException
        {
            ServletInputStream input = asyncContext.getRequest().getInputStream();
            _logger.debug("Asynchronous read start from {}", input);
            // First check for isReady() because it has
            // side effects, and then for isFinished().
            while (input.isReady() && !input.isFinished())
            {
                int read = input.read(buffer);
                _logger.debug("Asynchronous read {} bytes from {}", read, input);
                if (read >= 0)
                    append(buffer, 0, read);
            }
            if (!input.isFinished())
                _logger.debug("Asynchronous read pending from {}", input);
        }

        protected abstract void append(byte[] buffer, int offset, int length);

        @Override
        public void onAllDataRead() throws IOException
        {
            ServletInputStream input = asyncContext.getRequest().getInputStream();
            String json = finish();
            _logger.debug("Asynchronous read end from {}: {}", input, json);
            process(json);
        }

        protected abstract String finish();

        protected void process(String json) throws IOException
        {
            HttpServletRequest request = (HttpServletRequest)asyncContext.getRequest();
            HttpServletResponse response = (HttpServletResponse)asyncContext.getResponse();
            getBayeux().setCurrentTransport(AsyncJSONTransport.this);
            setCurrentRequest(request);
            try
            {
                ServerMessage.Mutable[] messages = parseMessages(json);
                _logger.debug("Parsed {} messages", messages.length);
                processMessages(request, response, messages);
            }
            catch (ParseException x)
            {
                handleJSONParseException(request, response, json, x);
                asyncContext.complete();
            }
            finally
            {
                setCurrentRequest(null);
                getBayeux().setCurrentTransport(null);
            }
        }

        @Override
        public void onError(Throwable throwable)
        {
            error(asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected class UTF8Reader extends AbstractReader
    {
        private final Utf8StringBuilder content = new Utf8StringBuilder(CAPACITY);

        protected UTF8Reader(AsyncContext asyncContext)
        {
            super(asyncContext);
        }

        @Override
        protected void append(byte[] buffer, int offset, int length)
        {
            content.append(buffer, offset, length);
        }

        @Override
        protected String finish()
        {
            return content.toString();
        }
    }

    protected class CharsetReader extends AbstractReader
    {
        private byte[] content = new byte[CAPACITY];
        private final Charset charset;
        private int count;

        public CharsetReader(AsyncContext asyncContext, Charset charset)
        {
            super(asyncContext);
            this.charset = charset;
        }

        @Override
        protected void append(byte[] buffer, int offset, int length)
        {
            int size = content.length;
            int newSize = size;
            while (newSize - count < length)
                newSize <<= 1;

            if (newSize < 0)
                throw new IllegalArgumentException("Message too large");

            if (newSize != size)
            {
                byte[] newContent = new byte[newSize];
                System.arraycopy(content, 0, newContent, 0, count);
                content = newContent;
            }

            System.arraycopy(buffer, offset, content, count, length);
            count += length;
        }

        @Override
        protected String finish()
        {
            return new String(content, 0, count, charset);
        }
    }

    protected class Writer implements WriteListener
    {
        private final StringBuilder buffer = new StringBuilder(512);
        private final AsyncContext asyncContext;
        private final ServerSessionImpl session;
        private final boolean startInterval;
        private final List<ServerMessage> messages;
        private final ServerMessage.Mutable[] replies;
        private int messageIndex = -1;
        private int replyIndex;

        public Writer(AsyncContext asyncContext, ServerSessionImpl session, boolean startInterval, List<ServerMessage> messages, ServerMessage.Mutable[] replies)
        {
            this.asyncContext = asyncContext;
            this.session = session;
            this.startInterval = startInterval;
            this.messages = messages;
            this.replies = replies;
        }

        @Override
        public void onWritePossible() throws IOException
        {
            ServletOutputStream output;
            try
            {
                output = asyncContext.getResponse().getOutputStream();

                if (messageIndex < 0)
                {
                    messageIndex = 0;
                    buffer.append("[");
                }

                _logger.debug("Messages to write for session {}: {}", session, messages.size());
                while (messageIndex < messages.size())
                {
                    if (messageIndex > 0)
                        buffer.append(",");

                    buffer.append(messages.get(messageIndex++).getJSON());
                    output.write(buffer.toString().getBytes("UTF-8"));
                    buffer.setLength(0);
                    if (!output.isReady())
                        return;
                }
            }
            finally
            {
                // Start the interval timeout after writing the messages
                // since they may take time to be written, even in case
                // of exceptions to make sure the session can be swept.
                if (replyIndex == 0 && startInterval && session != null && session.isConnected())
                    session.startIntervalTimeout(getInterval());
            }

            _logger.debug("Replies to write for session {}: {}", session, replies.length);
            boolean needsComma = messageIndex > 0;
            while (replyIndex < replies.length)
            {
                ServerMessage.Mutable reply = replies[replyIndex++];
                if (reply == null)
                    continue;

                if (needsComma)
                    buffer.append(",");
                needsComma = true;

                buffer.append(reply.getJSON());

                if (replyIndex == replies.length)
                    buffer.append("]");

                output.write(buffer.toString().getBytes("UTF-8"));
                buffer.setLength(0);
                if (!output.isReady())
                    return;
            }

            asyncContext.complete();
        }

        @Override
        public void onError(Throwable throwable)
        {
            error(asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private class AsyncLongPollScheduler extends LongPollScheduler
    {
        private AsyncLongPollScheduler(AsyncContext asyncContext, ServerSessionImpl session, ServerMessage.Mutable reply, String browserId, long timeout)
        {
            super(asyncContext, session, reply, browserId, timeout);
        }

        @Override
        protected void dispatch()
        {
            // Direct call to resume() to write the messages in the queue and the replies.
            // Since the write is async, we will never block here and thus never delay other sessions.
            resume(getAsyncContext(), getServerSession(), getMetaConnectReply());
        }

        @Override
        protected void error(int code)
        {
            AsyncJSONTransport.this.error(getAsyncContext(), code);
        }
    }
}
