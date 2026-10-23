using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using Nova.Core;
using Nova.Core.Streaming;

namespace Nova.App
{
    /// <summary>
    /// The parental age filter and word filter in the app: settings (PIN-protected), the Filter folder
    /// (settings and PIN come back after a reinstall), the film-database check, and redrawing the lists.
    /// </summary>
    public static class Filtering
    {
        private static DispatcherTimer _redraw;
        private static DispatcherTimer _check;
        private static CancellationTokenSource _cts;

        /// <summary>The lists must be drawn again (UI thread).</summary>
        public static event Action Changed;

        public static void Start()
        {
            var st = App.Store.State;
            if (!st.Filter.Set)
            {
                // New install: the filter (and PIN) saved earlier on this PC.
                var saved = App.Filter.SavedSettings();
                if (saved != null)
                {
                    st.Filter = saved.Item1;
                    if (!App.Store.HasPin && !string.IsNullOrEmpty(saved.Item2)) { st.PinHash = saved.Item2; st.PinSalt = saved.Item3; }
                }
                st.Filter.Set = true;
                App.Store.SaveState();
            }
            _redraw = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(400) };
            _redraw.Tick += (s, e) => { _redraw.Stop(); var h = Changed; if (h != null) h(); };
            _check = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
            _check.Tick += (s, e) => { _check.Stop(); RunCheck(); };
            App.Filter.Changed += () => Application.Current.Dispatcher.BeginInvoke(new Action(() => { _redraw.Stop(); _redraw.Start(); }));
            App.Filter.Configure(st.Filter);
            Task.Run(() => App.Filter.Load());
            App.Filter.SaveSettings(st.Filter, st.PinHash, st.PinSalt);
            App.Store.OrgChanged += () => App.Filter.SaveSettings(App.Store.State.Filter, App.Store.State.PinHash, App.Store.State.PinSalt);
        }

        public static FilterSettings Settings { get { return App.Store.State.Filter; } }

        /// <summary>Changes the filter (the caller checks the PIN first).</summary>
        public static void Update(Action<FilterSettings> change)
        {
            var f = App.Store.State.Filter.Copy();
            change(f);
            f.Set = true;
            App.Store.State.Filter = f;
            App.Store.SaveState();
            App.Filter.Configure(f);
            App.Filter.SaveSettings(f, App.Store.State.PinHash, App.Store.State.PinSalt);
            CheckSoon();
        }

        /// <summary>After a restore: use the filter of the backup.</summary>
        public static void Reload()
        {
            App.Filter.Configure(App.Store.State.Filter);
            App.Filter.SaveSettings(App.Store.State.Filter, App.Store.State.PinHash, App.Store.State.PinSalt);
            CheckSoon();
        }

        public static bool Hides(Movie m, string group) { return App.Filter != null && App.Filter.Blocked(m, group); }
        public static bool Hides(Series s, string group) { return App.Filter != null && App.Filter.Blocked(s, group); }
        public static bool Hides(LiveChannel c, string group) { return App.Filter != null && App.Filter.LiveBlocked(c, group); }
        public static bool HidesGroup(string zone, Category c) { return App.Filter != null && App.Filter.GroupBlocked(zone, c.Key, c.Name); }

        /// <summary>Connections Nova itself uses on this account now (playing + downloading), for the status dots.</summary>
        public static int ConnectionsUsed(string accountId)
        {
            if (accountId == null) return 0;
            var n = 0;
            try
            {
                if (App.Hub.BusyAccounts(null).Contains(accountId)) n++;
                n += App.Downloads.Items.Count(i => i.Status == DownloadStatus.Downloading && i.FromServer && i.AccountId == accountId);
            }
            catch { }
            return n;
        }

        /// <summary>Every film, series and channel of every account, with group names (from the search index).</summary>
        public static async Task<List<AgeFilter.AccountIndex>> Snapshot()
        {
            var list = new List<AgeFilter.AccountIndex>();
            var zones = new[] { ContentType.Live, ContentType.Movie, ContentType.Series };
            var groups = new Dictionary<string, string>();
            foreach (var z in zones)
            {
                try
                {
                    var code = Organize.Zone(z);
                    foreach (var sec in await App.Catalog.Sections(z))
                        foreach (var c in sec.Groups) groups[code + "|" + c.AccountId + "|" + c.Id] = c.Name;
                }
                catch { }
            }
            foreach (var a in App.Store.Accounts.ToList())
            {
                var x = new AgeFilter.AccountIndex { Account = a, Groups = groups };
                try { x.Movies = await App.Catalog.AllMovies(a); } catch { }
                try { x.Series = await App.Catalog.AllSeries(a); } catch { }
                try { x.Live = await App.Catalog.AllChannels(a); } catch { }
                list.Add(x);
            }
            return list;
        }

        /// <summary>Checks the ages a little later (several changes in a row make one check).</summary>
        public static void CheckSoon()
        {
            if (_check == null) return;
            _check.Stop();
            _check.Start();
        }

        private static async void RunCheck()
        {
            if (_cts != null) _cts.Cancel();
            var cts = new CancellationTokenSource();
            _cts = cts;
            try
            {
                var index = await Snapshot();
                if (cts.IsCancellationRequested) return;
                await Task.Run(() => App.Filter.Check(index, cts.Token));
                await Task.Run(() => App.Filter.WriteHiddenList(index));
            }
            catch (Exception e) { App.Log(e); }
        }
    }
}
