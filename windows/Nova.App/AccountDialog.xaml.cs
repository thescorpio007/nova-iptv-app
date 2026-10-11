using System;
using System.Threading;
using System.Windows;
using System.Windows.Media;
using Nova.Core;

namespace Nova.App
{
    /// <summary>Add or edit an Xtream account. Checks the login before saving.</summary>
    public partial class AccountDialog : Window
    {
        private readonly Account _account;
        private CancellationTokenSource _cts;
        private bool _filling;

        private AccountDialog(Account existing)
        {
            InitializeComponent();
            _account = existing != null ? existing.Copy() : new Account();
            Heading.Text = existing != null ? "Edit account" : "Add account";
            Title = Heading.Text;
            NameBox.Text = _account.Name ?? "";
            ServerBox.Text = _account.Server ?? "";
            UserBox.Text = _account.Username ?? "";
            PassBox.Text = _account.Password ?? "";
            Loaded += (s, e) => { (existing == null ? ServerBox : NameBox).Focus(); };
            Closed += (s, e) => { if (_cts != null) _cts.Cancel(); };
        }

        /// <summary>Opens the dialog; returns true when the account was saved.</summary>
        public static bool Edit(Window owner, Account existing)
        {
            var d = new AccountDialog(existing);
            if (owner != null && owner.IsVisible) d.Owner = owner;
            return d.ShowDialog() == true;
        }

        private void OnServerChanged(object sender, System.Windows.Controls.TextChangedEventArgs e)
        {
            if (_filling) return;
            string server, user, pass;
            if (XtreamApi.TryParseLink(ServerBox.Text, out server, out user, out pass))
            {
                _filling = true;
                ServerBox.Text = server;
                ServerBox.CaretIndex = server.Length;
                UserBox.Text = user;
                PassBox.Text = pass;
                _filling = false;
                SetStatus("Link recognised: server, username and password filled in.", false);
            }
        }

        private bool Collect()
        {
            _account.Name = NameBox.Text.Trim();
            _account.Server = ServerBox.Text.Trim();
            _account.Username = UserBox.Text.Trim();
            _account.Password = PassBox.Text.Trim();
            if (_account.Server.Length == 0 || _account.Username.Length == 0 || _account.Password.Length == 0)
            {
                SetStatus("Please fill in the server, username and password.", true);
                return false;
            }
            return true;
        }

        private async void OnSignIn(object sender, RoutedEventArgs e)
        {
            if (!Collect()) return;
            SignIn.IsEnabled = false;
            SaveAnyway.Visibility = Visibility.Collapsed;
            SetStatus("Checking your account…", false);
            if (_cts != null) _cts.Cancel();
            var cts = new CancellationTokenSource(TimeSpan.FromSeconds(25));
            _cts = cts;
            try
            {
                var info = await App.Catalog.Api.Authenticate(_account, cts.Token);
                Save();
                App.Home.Toast("Signed in · " + info.Summary);
            }
            catch (Exception ex)
            {
                if (!IsLoaded) return;
                var msg = ex is OperationCanceledException ? "The server did not answer in time." : ex.Message;
                SetStatus(msg + "\nIf your provider is blocked without your VPN, turn the VPN on and try again.", true);
                SaveAnyway.Visibility = Visibility.Visible;
                SignIn.IsEnabled = true;
            }
        }

        private void OnSaveAnyway(object sender, RoutedEventArgs e)
        {
            if (!Collect()) return;
            Save();
        }

        private void Save()
        {
            _account.Server = XtreamApi.NormalizeServer(_account.Server);
            App.Store.SaveAccount(_account);
            DialogResult = true;
        }

        private void SetStatus(string text, bool error)
        {
            Status.Text = text;
            Status.Foreground = (Brush)FindResource(error ? "ErrorBrush" : "SubText");
        }
    }
}
