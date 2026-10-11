using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Text;

namespace Nova.Core.Streaming
{
    /// <summary>
    /// A stream download over a plain socket. Used instead of HttpClient for video because
    /// closing it really closes the connection (important for 1-connection accounts) and every
    /// read has a time limit (the watchdog notices a frozen stream).
    /// </summary>
    public sealed class RawResponse : IDisposable
    {
        public int Status { get; internal set; }
        public string Reason { get; internal set; }
        public Dictionary<string, string> Headers { get; } = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        public Stream Body { get; internal set; }
        internal TcpClient Client;
        internal Stream Raw;

        /// <summary>Length of this answer's body, or -1.</summary>
        public long ContentLength
        {
            get
            {
                string v; long n;
                return Headers.TryGetValue("Content-Length", out v) && long.TryParse(v.Trim(), out n) ? n : -1;
            }
        }

        /// <summary>Full size of the file (from Content-Range on a partial answer), or -1.</summary>
        public long TotalLength
        {
            get
            {
                string v;
                if (Status == 206 && Headers.TryGetValue("Content-Range", out v))
                {
                    var slash = v.LastIndexOf('/');
                    long n;
                    if (slash >= 0 && long.TryParse(v.Substring(slash + 1).Trim(), out n)) return n;
                    return -1;
                }
                return Status == 200 ? ContentLength : -1;
            }
        }

        /// <summary>Where a partial answer starts (Content-Range), 0 for a full answer.</summary>
        public long RangeStart
        {
            get
            {
                string v;
                if (Status != 206 || !Headers.TryGetValue("Content-Range", out v)) return 0;
                // "bytes 100-199/1000"
                var s = v.Trim();
                var sp = s.IndexOf(' ');
                var dash = s.IndexOf('-');
                long n;
                return sp >= 0 && dash > sp && long.TryParse(s.Substring(sp + 1, dash - sp - 1), out n) ? n : 0;
            }
        }

        public void Dispose()
        {
            try { if (Raw != null) Raw.Dispose(); } catch { }
            try { if (Client != null) Client.Close(); } catch { }
        }
    }

    public static class RawHttp
    {
        /// <summary>
        /// Opens url (following up to 5 redirects). from &gt;= 0 asks for a byte range starting there.
        /// readTimeoutMs limits every single read: a stream that stops sending throws an IOException.
        /// </summary>
        public static RawResponse Open(string url, string userAgent, long from, int connectTimeoutMs, int readTimeoutMs)
        {
            var current = url;
            for (int hop = 0; hop < 6; hop++)
            {
                var resp = OpenOnce(current, userAgent, from, connectTimeoutMs, readTimeoutMs);
                if (resp.Status >= 300 && resp.Status < 400 && resp.Status != 304)
                {
                    string loc;
                    resp.Dispose();
                    if (!resp.Headers.TryGetValue("Location", out loc) || string.IsNullOrWhiteSpace(loc))
                        throw new IOException("Redirect without an address");
                    current = new Uri(new Uri(current), loc.Trim()).AbsoluteUri;
                    continue;
                }
                return resp;
            }
            throw new IOException("Too many redirects");
        }

        private static RawResponse OpenOnce(string url, string userAgent, long from, int connectTimeoutMs, int readTimeoutMs)
        {
            var uri = new Uri(url);
            var https = uri.Scheme == Uri.UriSchemeHttps;
            var client = new TcpClient { NoDelay = true, ReceiveBufferSize = 256 * 1024 };
            var resp = new RawResponse { Client = client };
            try
            {
                var connect = client.BeginConnect(uri.Host, uri.Port, null, null);
                if (!connect.AsyncWaitHandle.WaitOne(connectTimeoutMs))
                    throw new IOException("The server did not answer (connection timed out)");
                client.EndConnect(connect);
                client.ReceiveTimeout = readTimeoutMs;
                client.SendTimeout = connectTimeoutMs;
                Stream s = client.GetStream();
                if (https)
                {
                    var ssl = new SslStream(s, false, (a, b, c, d) => true);
                    ssl.AuthenticateAsClient(uri.Host, null, SslProtocols.Tls12 | SslProtocols.Tls11 | SslProtocols.Tls, false);
                    s = ssl;
                }
                resp.Raw = s;

                var req = new StringBuilder();
                req.Append("GET ").Append(uri.PathAndQuery).Append(" HTTP/1.1\r\n");
                req.Append("Host: ").Append(uri.IsDefaultPort ? uri.Host : uri.Host + ":" + uri.Port).Append("\r\n");
                req.Append("User-Agent: ").Append(userAgent).Append("\r\n");
                req.Append("Accept: */*\r\n");
                req.Append("Icy-MetaData: 0\r\n");
                if (from >= 0) req.Append("Range: bytes=").Append(from.ToString(CultureInfo.InvariantCulture)).Append("-\r\n");
                req.Append("Connection: close\r\n\r\n");
                var bytes = Encoding.ASCII.GetBytes(req.ToString());
                s.Write(bytes, 0, bytes.Length);
                s.Flush();

                var reader = new HeaderReader(s);
                var status = reader.ReadLine();
                if (status == null) throw new IOException("The server closed the connection");
                // "HTTP/1.1 206 Partial Content"
                var parts = status.Split(new[] { ' ' }, 3);
                int code;
                if (parts.Length < 2 || !int.TryParse(parts[1], out code)) throw new IOException("Not an HTTP answer");
                resp.Status = code;
                resp.Reason = parts.Length > 2 ? parts[2] : "";
                string line;
                while (!string.IsNullOrEmpty(line = reader.ReadLine()))
                {
                    var colon = line.IndexOf(':');
                    if (colon <= 0) continue;
                    resp.Headers[line.Substring(0, colon).Trim()] = line.Substring(colon + 1).Trim();
                }
                Stream body = new PrefixStream(reader.Leftover(), s);
                string te;
                if (resp.Headers.TryGetValue("Transfer-Encoding", out te) && te.IndexOf("chunked", StringComparison.OrdinalIgnoreCase) >= 0)
                    body = new ChunkedStream(body);
                resp.Body = body;
                return resp;
            }
            catch
            {
                resp.Dispose();
                throw;
            }
        }

        /// <summary>Reads the header lines byte by byte and keeps whatever body bytes came along.</summary>
        private sealed class HeaderReader
        {
            private readonly Stream _s;
            private readonly byte[] _buf = new byte[16 * 1024];
            private int _pos, _len;

            public HeaderReader(Stream s) { _s = s; }

            public string ReadLine()
            {
                var sb = new StringBuilder();
                while (true)
                {
                    if (_pos >= _len)
                    {
                        _len = _s.Read(_buf, 0, _buf.Length);
                        _pos = 0;
                        if (_len <= 0) return sb.Length > 0 ? sb.ToString() : null;
                    }
                    var b = _buf[_pos++];
                    if (b == '\n') return sb.ToString().TrimEnd('\r');
                    sb.Append((char)b);
                    if (sb.Length > 16 * 1024) throw new IOException("Header line too long");
                }
            }

            public byte[] Leftover()
            {
                var rest = new byte[_len - _pos];
                Buffer.BlockCopy(_buf, _pos, rest, 0, rest.Length);
                return rest;
            }
        }

        private sealed class PrefixStream : Stream
        {
            private readonly byte[] _prefix;
            private int _p;
            private readonly Stream _inner;

            public PrefixStream(byte[] prefix, Stream inner) { _prefix = prefix; _inner = inner; }

            public override int Read(byte[] buffer, int offset, int count)
            {
                if (_p < _prefix.Length)
                {
                    var n = Math.Min(count, _prefix.Length - _p);
                    Buffer.BlockCopy(_prefix, _p, buffer, offset, n);
                    _p += n;
                    return n;
                }
                return _inner.Read(buffer, offset, count);
            }

            public override bool CanRead { get { return true; } }
            public override bool CanSeek { get { return false; } }
            public override bool CanWrite { get { return false; } }
            public override long Length { get { throw new NotSupportedException(); } }
            public override long Position { get { throw new NotSupportedException(); } set { throw new NotSupportedException(); } }
            public override void Flush() { }
            public override long Seek(long offset, SeekOrigin origin) { throw new NotSupportedException(); }
            public override void SetLength(long value) { throw new NotSupportedException(); }
            public override void Write(byte[] buffer, int offset, int count) { throw new NotSupportedException(); }
        }

        /// <summary>Decodes "Transfer-Encoding: chunked".</summary>
        private sealed class ChunkedStream : Stream
        {
            private readonly Stream _inner;
            private long _left;
            private bool _done;

            public ChunkedStream(Stream inner) { _inner = inner; }

            private string Line()
            {
                var sb = new StringBuilder();
                int b;
                while ((b = _inner.ReadByte()) >= 0)
                {
                    if (b == '\n') return sb.ToString().TrimEnd('\r');
                    sb.Append((char)b);
                    if (sb.Length > 1024) throw new IOException("Bad chunk header");
                }
                return null;
            }

            public override int Read(byte[] buffer, int offset, int count)
            {
                if (_done) return 0;
                if (_left == 0)
                {
                    var line = Line();
                    if (line == "") line = Line(); // end of the previous chunk
                    if (line == null) { _done = true; return 0; }
                    var semi = line.IndexOf(';');
                    if (semi >= 0) line = line.Substring(0, semi);
                    _left = long.Parse(line.Trim(), NumberStyles.HexNumber, CultureInfo.InvariantCulture);
                    if (_left == 0) { _done = true; return 0; }
                }
                var n = _inner.Read(buffer, offset, (int)Math.Min(count, _left));
                if (n <= 0) { _done = true; return 0; }
                _left -= n;
                return n;
            }

            public override bool CanRead { get { return true; } }
            public override bool CanSeek { get { return false; } }
            public override bool CanWrite { get { return false; } }
            public override long Length { get { throw new NotSupportedException(); } }
            public override long Position { get { throw new NotSupportedException(); } set { throw new NotSupportedException(); } }
            public override void Flush() { }
            public override long Seek(long offset, SeekOrigin origin) { throw new NotSupportedException(); }
            public override void SetLength(long value) { throw new NotSupportedException(); }
            public override void Write(byte[] buffer, int offset, int count) { throw new NotSupportedException(); }
        }
    }
}
