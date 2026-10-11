using System.Linq;
using System.Windows;
using Nova.Core;

namespace Nova.App
{
    /// <summary>
    /// Accounts that allow only one connection: before something new plays, a download on that
    /// account steps aside, and a recording of another channel needs your OK to stop.
    /// </summary>
    public static class ConnectionGuard
    {
        /// <summary>True when it may play. exceptChannelKey = the channel itself (sharing its stream is fine).</summary>
        public static bool Allow(string accountId, string exceptChannelKey, Window owner)
        {
            if (accountId == null) return true;
            App.Downloads.Yield(accountId);
            if (App.Catalog.MaxConnections(accountId) != 1) return true;
            var others = App.Recordings.RecordingKeys.Where(k => k.StartsWith(accountId + "|") && k != exceptChannelKey).ToList();
            if (others.Count == 0) return true;
            var name = App.Recordings.NameOf(others[0]);
            var answer = MessageBox.Show(owner,
                "Your account allows only one connection, and it is recording \"" + name + "\".\n\nStop the recording to watch this?",
                "Nova IPTV", MessageBoxButton.YesNo, MessageBoxImage.Question);
            if (answer != MessageBoxResult.Yes) return false;
            foreach (var k in others) App.Recordings.Stop(k);
            return true;
        }
    }
}
