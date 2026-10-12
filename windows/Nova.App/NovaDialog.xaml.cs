using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;

namespace Nova.App
{
    /// <summary>
    /// Nova's small questions (dark, remote friendly). Yes / No questions start on No (as on Android),
    /// Esc always answers No / Cancel.
    /// </summary>
    public partial class NovaDialog : Window
    {
        private Func<string, string> _check;
        private int _choice = -1;

        private NovaDialog()
        {
            InitializeComponent();
        }

        private static Window OwnerWindow(Window owner)
        {
            if (owner != null && owner.IsVisible) return owner;
            var active = Application.Current.Windows.OfType<Window>().FirstOrDefault(w => w.IsActive);
            return active ?? Application.Current.MainWindow;
        }

        private static NovaDialog Make(Window owner, string heading, string message)
        {
            var d = new NovaDialog();
            var o = OwnerWindow(owner);
            if (o != null && o.IsVisible && !ReferenceEquals(o, d)) d.Owner = o;
            d.Heading.Text = heading ?? "";
            d.Heading.Visibility = string.IsNullOrEmpty(heading) ? Visibility.Collapsed : Visibility.Visible;
            d.Message.Text = message ?? "";
            d.Message.Visibility = string.IsNullOrEmpty(message) ? Visibility.Collapsed : Visibility.Visible;
            return d;
        }

        /// <summary>A Yes / No question; the highlight starts on No.</summary>
        public static bool Ask(Window owner, string heading, string message, string yes = "Yes", string no = "No")
        {
            var d = Make(owner, heading, message);
            d.YesBtn.Content = yes;
            d.NoBtn.Content = no;
            d.Loaded += (s, e) => d.NoBtn.Focus();
            return d.ShowDialog() == true;
        }

        public static void Info(Window owner, string heading, string message)
        {
            var d = Make(owner, heading, message);
            d.NoBtn.Visibility = Visibility.Collapsed;
            d.YesBtn.Content = "OK";
            d.Loaded += (s, e) => d.YesBtn.Focus();
            d.ShowDialog();
        }

        /// <summary>Asks for a text (or a hidden password). check returns an error message, or null when it's fine.</summary>
        public static string AskText(Window owner, string heading, string message, string initial, bool secret,
            string ok = "OK", Func<string, string> check = null)
        {
            var d = Make(owner, heading, message);
            d.YesBtn.Content = ok;
            d.NoBtn.Content = "Cancel";
            d._check = check;
            if (secret) { d.Secret.Visibility = Visibility.Visible; d.Loaded += (s, e) => d.Secret.Focus(); }
            else
            {
                d.Input.Visibility = Visibility.Visible;
                d.Input.Text = initial ?? "";
                d.Loaded += (s, e) => { d.Input.Focus(); d.Input.SelectAll(); };
            }
            if (d.ShowDialog() != true) return null;
            return secret ? d.Secret.Password : d.Input.Text;
        }

        /// <summary>A list of choices; returns the index chosen, or -1.</summary>
        public static int Choose(Window owner, string heading, string message, IList<string> options, int current = -1)
        {
            var d = Make(owner, heading, message);
            d.Choices.Visibility = Visibility.Visible;
            d.Choices.ItemsSource = options.Select((o, i) => new { Label = (i == current ? "●  " : "     ") + o, Index = i }).ToList();
            d.YesBtn.Visibility = Visibility.Collapsed;
            d.NoBtn.Content = "Cancel";
            d.Loaded += (s, e) =>
            {
                d.Choices.UpdateLayout();
                var c = d.Choices.ItemContainerGenerator.ContainerFromIndex(Math.Max(0, current)) as ContentPresenter;
                var b = c != null && System.Windows.Media.VisualTreeHelper.GetChildrenCount(c) > 0 ? System.Windows.Media.VisualTreeHelper.GetChild(c, 0) as Button : null;
                if (b != null) b.Focus(); else d.NoBtn.Focus();
            };
            return d.ShowDialog() == true ? d._choice : -1;
        }

        private void OnChoice(object sender, RoutedEventArgs e)
        {
            _choice = (int)((FrameworkElement)sender).Tag;
            DialogResult = true;
        }

        private void OnYes(object sender, RoutedEventArgs e)
        {
            if (_check != null)
            {
                var text = Secret.Visibility == Visibility.Visible ? Secret.Password : Input.Text;
                var problem = _check(text);
                if (problem != null)
                {
                    Error.Text = problem;
                    Error.Visibility = Visibility.Visible;
                    return;
                }
            }
            DialogResult = true;
        }

        private void OnNo(object sender, RoutedEventArgs e)
        {
            DialogResult = false;
        }

        private void OnKey(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Escape || e.Key == Key.BrowserBack) { e.Handled = true; DialogResult = false; }
            else if (e.Key == Key.Enter && (Input.IsKeyboardFocused || Secret.IsKeyboardFocused)) { e.Handled = true; OnYes(null, null); }
            else if ((e.Key == Key.Left || e.Key == Key.Right) && (NoBtn.IsKeyboardFocused || YesBtn.IsKeyboardFocused))
            {
                e.Handled = true;
                if (NoBtn.IsKeyboardFocused && YesBtn.IsVisible) YesBtn.Focus(); else if (NoBtn.IsVisible) NoBtn.Focus();
            }
        }
    }
}
