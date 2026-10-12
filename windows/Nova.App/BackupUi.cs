using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using Nova.Core;

namespace Nova.App
{
    /// <summary>Backup &amp; restore and the accounts link (same files and rules as the Android app).</summary>
    public static class BackupUi
    {
        // ---------------------------------------------------------------- backup

        /// <summary>Saves a .bak in Downloads\NovaIPTV\Backups (optionally password-protected). Returns the file.</summary>
        public static string Save(Window owner)
        {
            var choice = NovaDialog.Choose(owner, "Save backup",
                "A .bak file keeps all servers (with passwords), favorites, hidden items, order and settings. It works on the PC and on the box. " +
                "Keep it private, or protect it with a password.",
                new[] { "Save without password", "Save with a password" }, 1);
            if (choice < 0) return null;
            string password = null;
            if (choice == 1)
            {
                password = NovaDialog.AskText(owner, "Backup password", "Choose a password. You'll need it to restore this backup (on the PC or the box).",
                    null, true, "Next", p => p.Length < 4 ? "At least 4 characters, please." : null);
                if (password == null) return null;
                var again = NovaDialog.AskText(owner, "Backup password", "Type the password again.", null, true, "Save",
                    p => p == password ? null : "The two passwords are not the same.");
                if (again == null) return null;
            }
            try
            {
                var text = Backup.Export(App.Store);
                if (password != null) text = NovaCrypto.Encrypt(text, password);
                var folder = KnownFolders.Backups(App.Store.Settings);
                Directory.CreateDirectory(folder);
                var path = Path.Combine(folder, Backup.FileName(password != null));
                File.WriteAllText(path, text, new System.Text.UTF8Encoding(false));
                if (NovaDialog.Ask(owner, "Backup saved", path + "\n\nCopy it to a USB stick or to OneDrive to restore it on the box or another PC.", "Show the file", "OK"))
                {
                    try { System.Diagnostics.Process.Start("explorer.exe", "/select,\"" + path + "\""); } catch { }
                }
                return path;
            }
            catch (Exception e)
            {
                NovaDialog.Info(owner, "Couldn't save the backup", e.Message);
                return null;
            }
        }

        /// <summary>Restore: from a file on this PC, from a link, or from the last link used.</summary>
        public static void Restore(Window owner)
        {
            var last = App.Store.State.LastBackupLink;
            var options = new System.Collections.Generic.List<string> { "From a file on this PC (USB stick, Downloads…)", "From a web link (OneDrive, Google Drive, Dropbox, any http link)" };
            if (last != null) options.Add("Restore my backup  (" + Shorten(last) + ")");
            var c = NovaDialog.Choose(owner, "Restore a backup", "Everything in Nova is replaced by the backup (after a Yes / No question).", options, 0);
            if (c == 0)
            {
                var dlg = new Microsoft.Win32.OpenFileDialog { Title = "Choose a Nova backup (.bak)", Filter = "Nova backup|*.bak|All files|*.*" };
                var folder = KnownFolders.Backups(App.Store.Settings);
                if (Directory.Exists(folder)) dlg.InitialDirectory = folder;
                if (dlg.ShowDialog(owner) == true) RestoreFromFile(owner, dlg.FileName);
            }
            else if (c == 1)
            {
                var link = NovaDialog.AskText(owner, "Link to your .bak file", "OneDrive · Google Drive · Dropbox · your own site / NAS · any http(s) link", last, false, "Download");
                if (!string.IsNullOrWhiteSpace(link)) RestoreFromLink(owner, link.Trim());
            }
            else if (c == 2) RestoreFromLink(owner, last);
        }

        private static string Shorten(string s) { return s.Length > 42 ? s.Substring(0, 42) + "…" : s; }

        public static void RestoreFromFile(Window owner, string path)
        {
            string text;
            try { text = File.ReadAllText(path); }
            catch (Exception e) { NovaDialog.Info(owner, "Could not read " + Path.GetFileName(path), e.Message); return; }
            Apply(owner, text, Path.GetFileName(path));
        }

        public static async void RestoreFromLink(Window owner, string link)
        {
            App.Home.Toast("Downloading the backup…");
            string text;
            try { text = await LinkFetcher.FetchText(link, App.Store.Settings.UserAgent); }
            catch (Exception e) { NovaDialog.Info(owner, "Couldn't download the backup", e.Message); return; }
            if (Apply(owner, text, Shorten(link)))
            {
                App.Store.State.LastBackupLink = link;
                App.Store.State.LastBackupLinkAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                App.Store.SaveState();
            }
        }

        /// <summary>Opens (password if needed), shows what's inside, asks, then replaces everything.</summary>
        private static bool Apply(Window owner, string text, string name)
        {
            try
            {
                if (NovaCrypto.IsProtected(text))
                {
                    string opened = null;
                    var pw = NovaDialog.AskText(owner, "This backup is protected", "Enter its password.", null, true, "Open", p =>
                    {
                        try { opened = NovaCrypto.Decrypt(text, p); return null; }
                        catch (NovaException e) { return e.Message; }
                    });
                    if (pw == null || opened == null) return false;
                    text = opened;
                }
                var c = Backup.Read(text, App.Store.Settings);
                if (!NovaDialog.Ask(owner, "Restore this backup?", name + "\n" + c.Summary + "\n\nEverything currently in Nova (accounts, favorites, order, settings) is replaced.", "Yes, restore"))
                    return false;
                // Keep this PC's own data that a box backup doesn't have.
                c.State.RecentLinks = c.State.RecentLinks.Count > 0 ? c.State.RecentLinks : App.Store.State.RecentLinks;
                if (c.State.KeyMap.Count == 0) c.State.KeyMap = App.Store.State.KeyMap;
                if (c.FromAndroid)
                {
                    // Folders and window are this PC's.
                    c.Settings.BufferFolder = App.Store.Settings.BufferFolder;
                    c.Settings.DownloadFolder = App.Store.Settings.DownloadFolder;
                }
                App.Catalog.Clear();
                App.Store.ReplaceAll(c.Accounts, c.Settings, c.Favorites, c.State);
                PinGate.Lock();
                App.Home.Toast("Backup restored: " + c.Summary);
                var ignored = App.Catalog.CheckAccounts();
                return true;
            }
            catch (NovaException e) { NovaDialog.Info(owner, "Can't restore", e.Message); }
            catch (Exception e) { App.Log(e); NovaDialog.Info(owner, "Can't restore", e.Message); }
            return false;
        }

        // ---------------------------------------------------------------- accounts link

        /// <summary>Connects an accounts list kept on OneDrive / Google Drive / Dropbox… (checked every day).</summary>
        public static async void ConnectAccountsLink(Window owner)
        {
            var link = NovaDialog.AskText(owner, "Accounts link",
                "A link to a text file with your accounts, one per line:\nName | server | username | password\n(or Name | your full M3U / Xtream link). Nova reads it every day and keeps your accounts up to date.",
                App.Store.State.AccountsLink, false, "Next");
            if (string.IsNullOrWhiteSpace(link)) return;
            link = link.Trim();
            App.Home.Toast("Reading your accounts list…");
            string text;
            try { text = await LinkFetcher.FetchText(link, App.Store.Settings.UserAgent); }
            catch (Exception e) { NovaDialog.Info(owner, "Couldn't read the list", e.Message); return; }
            string password = null;
            if (NovaCrypto.IsProtected(text))
            {
                string opened = null;
                password = NovaDialog.AskText(owner, "This list is protected", "Enter its password (it is remembered, for the daily check).", null, true, "Open", p =>
                {
                    try { opened = NovaCrypto.Decrypt(text, p); return null; }
                    catch (NovaException e) { return e.Message; }
                });
                if (password == null) return;
                text = opened;
            }
            var list = AccountsList.Parse(text);
            if (list.Count == 0) { NovaDialog.Info(owner, "No account found", "No account found in this file (one per line: Name | server | username | password)."); return; }
            if (!NovaDialog.Ask(owner, "Use this accounts list?", list.Count + " accounts: " + string.Join(", ", list.Select(l => l.Name)) +
                "\n\nAccounts with the same name are updated; new ones are added; accounts you typed by hand stay.", "Yes, use it")) return;
            string summary;
            var accounts = AccountsList.Apply(App.Store.Accounts.ToList(), list, out summary);
            App.Store.State.AccountsLink = link;
            App.Store.State.AccountsLinkPassword = password;
            App.Store.State.AccountsLinkChecked = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            App.Store.State.AccountsLinkNote = summary;
            App.Store.SaveState();
            App.Catalog.Clear();
            App.Store.SetAccounts(accounts);
            App.Home.Toast("Accounts link connected: " + summary);
        }

        /// <summary>Reads the link again and applies the changes. Never changes anything if the file can't be read.</summary>
        public static async Task<string> SyncAccountsLink()
        {
            var st = App.Store.State;
            if (st.AccountsLink == null) return "No accounts link";
            string note;
            try
            {
                var text = await LinkFetcher.FetchText(st.AccountsLink, App.Store.Settings.UserAgent);
                if (NovaCrypto.IsProtected(text)) text = NovaCrypto.Decrypt(text, st.AccountsLinkPassword ?? "");
                var list = AccountsList.Parse(text);
                if (list.Count == 0) throw new NovaException("the list is empty");
                var accounts = AccountsList.Apply(App.Store.Accounts.ToList(), list, out note);
                var changed = note != "Up to date" || accounts.Count != App.Store.Accounts.Count ||
                              accounts.Zip(App.Store.Accounts, (a, b) => a.Id != b.Id || a.FromList != b.FromList).Any(x => x);
                if (changed) { App.Catalog.Clear(); App.Store.SetAccounts(accounts); }
                st.AccountsLinkChecked = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            }
            catch (Exception e)
            {
                note = "Could not read your accounts link — nothing changed (" + e.Message + ")";
            }
            st.AccountsLinkNote = note;
            App.Store.SaveState();
            return note;
        }

        public static void DisconnectAccountsLink(Window owner)
        {
            if (!NovaDialog.Ask(owner, "Stop using the accounts link?", "Your accounts stay in Nova; they are just no longer updated from the link.", "Yes, stop")) return;
            var st = App.Store.State;
            st.AccountsLink = null; st.AccountsLinkPassword = null; st.AccountsLinkNote = null; st.AccountsLinkChecked = 0;
            App.Store.SaveState();
            App.Store.SetAccounts(App.Store.Accounts.Select(a => { var c = a.Copy(); c.FromList = false; return c; }).ToList());
        }

        /// <summary>Your accounts as a list file (optionally protected), to put on OneDrive etc.</summary>
        public static void SaveAccountsList(Window owner)
        {
            var c = NovaDialog.Choose(owner, "Save accounts list", "A text file with your accounts (to keep on OneDrive, Google Drive… and use as your accounts link).",
                new[] { "Save without password", "Save with a password" }, 1);
            if (c < 0) return;
            string pw = null;
            if (c == 1)
            {
                pw = NovaDialog.AskText(owner, "List password", "Choose a password for the list.", null, true, "Save", p => p.Length < 4 ? "At least 4 characters, please." : null);
                if (pw == null) return;
            }
            var text = AccountsList.Write(App.Store.Accounts);
            if (pw != null) text = NovaCrypto.Encrypt(text, pw);
            var folder = KnownFolders.Backups(App.Store.Settings);
            Directory.CreateDirectory(folder);
            var path = Path.Combine(folder, pw == null ? "NovaIPTV-accounts.txt" : "NovaIPTV-accounts-protected.txt");
            File.WriteAllText(path, text, new System.Text.UTF8Encoding(false));
            try { System.Diagnostics.Process.Start("explorer.exe", "/select,\"" + path + "\""); } catch { }
            App.Home.Toast("Saved: " + path);
        }
    }
}
