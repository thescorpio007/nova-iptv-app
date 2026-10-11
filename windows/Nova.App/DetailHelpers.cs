using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using Nova.Core;

namespace Nova.App
{
    /// <summary>Small helpers shared by the film and series pages.</summary>
    internal static class DetailHelpers
    {
        /// <summary>Rating out of 10 as five stars: ★★★★☆.</summary>
        public static string Stars(double rating)
        {
            var five = Math.Max(0, Math.Min(5, (int)Math.Round(rating / 2)));
            return new string('★', five) + new string('☆', 5 - five);
        }

        public static string RatingText(double rating)
        {
            return rating.ToString("0.#", System.Globalization.CultureInfo.InvariantCulture) + " / 10";
        }

        /// <summary>"2019 · 1h 52m · Action, Drama · USA" from whatever is known.</summary>
        public static string Meta(int? year, MediaDetails d)
        {
            var parts = new List<string>();
            var y = year.HasValue ? year.Value.ToString() : null;
            if (y == null && d != null && !string.IsNullOrEmpty(d.ReleaseDate) && d.ReleaseDate.Length >= 4) y = d.ReleaseDate.Substring(0, 4);
            if (y != null) parts.Add(y);
            if (d != null && !string.IsNullOrEmpty(d.Duration)) parts.Add(d.Duration);
            if (d != null && !string.IsNullOrEmpty(d.Genre)) parts.Add(d.Genre);
            if (d != null && !string.IsNullOrEmpty(d.Country)) parts.Add(d.Country);
            return string.Join("  ·  ", parts);
        }

        public static void SetFact(TextBlock label, TextBlock value, string text)
        {
            var has = !string.IsNullOrWhiteSpace(text);
            value.Text = has ? text.Trim() : "";
            label.Visibility = value.Visibility = has ? Visibility.Visible : Visibility.Collapsed;
        }

        public static void SetFavButton(Button b, bool isFav)
        {
            b.Content = isFav ? "★  In favorites" : "☆  Add to favorites";
        }

        public static void OpenTrailer(MediaDetails d)
        {
            var url = d == null ? null : d.TrailerUrl;
            if (url == null) return;
            try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
            catch (Exception e) { App.Home.Toast("Couldn't open the trailer: " + e.Message); }
        }
    }
}
