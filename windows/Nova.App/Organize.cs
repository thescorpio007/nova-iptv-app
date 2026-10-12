using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using Nova.Core;

namespace Nova.App
{
    /// <summary>
    /// Your organisation of the lists, same rules and same saved data as the Android app:
    /// hidden groups / items, your order of groups (per account) and items (per group), folded account sections,
    /// and the sort order of each zone.
    /// </summary>
    public static class Organize
    {
        public static string Zone(ContentType t)
        {
            return t == ContentType.Live ? "live" : t == ContentType.Movie ? "movie" : "series";
        }

        public const string NewKey = "__new__";

        /// <summary>Hidden things appear (faded) in edit mode only when no parental PIN is set.</summary>
        public static bool ShowsHidden(bool edit) { return edit && !App.Store.HasPin; }

        public static bool IsGroupHidden(string zone, string groupKey) { return App.Store.Org.HiddenGroups.Contains(zone + "|" + groupKey); }
        public static bool IsItemHidden(string zone, string itemKey) { return App.Store.Org.HiddenItems.Contains(zone + "|" + itemKey); }
        public static bool IsClosed(string zone, string accountId) { return App.Store.Org.ClosedSections.Contains(zone + "|" + accountId); }

        /// <summary>Every group of the zone in your order (all accounts, as one list of keys).</summary>
        public static List<Category> OrderedGroups(string zone, IEnumerable<GroupSection> sections)
        {
            List<string> order;
            App.Store.Org.GroupOrder.TryGetValue(zone, out order);
            return Org.ApplyOrder(sections.SelectMany(s => s.Groups).ToList(), order, c => c.Key);
        }

        /// <summary>The groups of one account section, in order, hidden ones only when showHidden.</summary>
        public static List<Category> Groups(string zone, List<Category> ordered, string accountId, bool showHidden)
        {
            return ordered.Where(c => c.AccountId == accountId && (showHidden || !IsGroupHidden(zone, c.Key))).ToList();
        }

        public static void SetClosed(string zone, string accountId, bool closed)
        {
            App.Store.UpdateOrg(o => { var k = zone + "|" + accountId; if (closed) o.ClosedSections.Add(k); else o.ClosedSections.Remove(k); });
        }

        public static void ToggleGroupHidden(string zone, Category c, string label)
        {
            var k = zone + "|" + c.Key;
            App.Store.UpdateOrg(o =>
            {
                if (o.HiddenGroups.Remove(k)) o.Names.Remove(k);
                else { o.HiddenGroups.Add(k); o.Names[k] = label; }
            });
        }

        public static void ToggleItemHidden(string zone, string itemKey, string label)
        {
            var k = zone + "|" + itemKey;
            App.Store.UpdateOrg(o =>
            {
                if (o.HiddenItems.Remove(k)) o.Names.Remove(k);
                else { o.HiddenItems.Add(k); o.Names[k] = label; }
            });
        }

        /// <summary>Moves a group within its account section: delta -1 / +1, or to the top / bottom (int.MinValue / int.MaxValue).</summary>
        public static void MoveGroup(string zone, List<Category> allOrdered, Category c, int delta)
        {
            var full = allOrdered.Select(x => x.Key).ToList();
            var section = full.Where(k => k.StartsWith(c.AccountId + "|")).ToList();
            List<string> moved;
            if (delta == int.MinValue) moved = Org.MoveTo(section, c.Key, true);
            else if (delta == int.MaxValue) moved = Org.MoveTo(section, c.Key, false);
            else moved = Org.Move(section, c.Key, delta);
            var i = 0;
            var merged = full.Select(k => k.StartsWith(c.AccountId + "|") ? moved[i++] : k).ToList();
            App.Store.UpdateOrg(o => o.GroupOrder[zone] = merged);
        }

        /// <summary>Moves an item inside a group (keys = the group's items in the current order).</summary>
        public static void MoveItem(string zone, string groupKey, List<string> keys, string key, int delta)
        {
            List<string> moved;
            if (delta == int.MinValue) moved = Org.MoveTo(keys, key, true);
            else if (delta == int.MaxValue) moved = Org.MoveTo(keys, key, false);
            else moved = Org.Move(keys, key, delta);
            App.Store.UpdateOrg(o => o.ItemOrder[zone + "|" + groupKey] = moved);
        }

        /// <summary>Items of a group: your order (or the chosen sort), hidden ones only when showHidden.</summary>
        public static List<T> Items<T>(string zone, string groupKey, IList<T> list, Func<T, string> key, bool edit, string sort, Func<T, SortInfo> info)
        {
            List<T> shown;
            if (groupKey == Catalog.FavKey) shown = list.ToList();
            else if (groupKey == NewKey) shown = list.Where(x => !IsItemHidden(zone, key(x))).ToList();
            else
            {
                List<string> order;
                App.Store.Org.ItemOrder.TryGetValue(zone + "|" + groupKey, out order);
                var ordered = Org.ApplyOrder(list, order, key);
                shown = ShowsHidden(edit) ? ordered : ordered.Where(x => !IsItemHidden(zone, key(x))).ToList();
            }
            if (edit || groupKey == NewKey || sort == "custom" || string.IsNullOrEmpty(sort)) return shown;
            return Sort(shown, sort, info);
        }

        public static List<T> Sort<T>(List<T> list, string sort, Func<T, SortInfo> info)
        {
            switch (sort)
            {
                case "added": return list.OrderByDescending(x => info(x).Added).ToList();
                case "year": return list.OrderByDescending(x => info(x).Year ?? 0).ThenByDescending(x => info(x).Added).ToList();
                case "rating": return list.OrderByDescending(x => info(x).Rating ?? 0).ThenBy(x => info(x).Name, StringComparer.OrdinalIgnoreCase).ToList();
                case "number": return list.OrderBy(x => info(x).Num <= 0 ? int.MaxValue : info(x).Num).ToList();
                case "name": return list.OrderBy(x => CleanName(info(x).Name), StringComparer.OrdinalIgnoreCase).ToList();
                default: return list;
            }
        }

        /// <summary>"|FR| Film" sorts as "Film".</summary>
        private static string CleanName(string n)
        {
            if (string.IsNullOrEmpty(n)) return "";
            var s = n.Trim();
            if (s.StartsWith("|") || s.StartsWith("["))
            {
                var close = s.IndexOfAny(new[] { '|', ']' }, 1);
                if (close > 0 && close < 12) s = s.Substring(close + 1).Trim();
            }
            return s;
        }

        public static SortInfo Info(LiveChannel c) { return new SortInfo { Added = c.Added, Num = c.Num, Name = c.Name }; }
        public static SortInfo Info(Movie m) { return new SortInfo { Added = m.Added, Year = m.Year, Rating = ParseRating(m.Rating), Name = m.Name }; }
        public static SortInfo Info(Series s) { return new SortInfo { Added = s.Added, Year = s.Year, Rating = ParseRating(s.Rating), Name = s.Name }; }

        private static double? ParseRating(string r)
        {
            double d;
            return r != null && double.TryParse(r, NumberStyles.Float, CultureInfo.InvariantCulture, out d) ? d : (double?)null;
        }

        /// <summary>Sort choices of a zone (code, label).</summary>
        public static List<Tuple<string, string>> SortChoices(string zone)
        {
            var list = new List<Tuple<string, string>> { T("custom", "My order"), T("added", "Recently added") };
            if (zone == "live") list.Add(T("number", "Channel number"));
            else { list.Add(T("year", "Year (newest)")); }
            list.Add(T("name", "Name A → Z"));
            if (zone != "live") list.Add(T("rating", "Rating (highest)"));
            return list;
        }

        public static string SortLabel(string zone, string code)
        {
            var c = SortChoices(zone).FirstOrDefault(x => x.Item1 == code);
            return c == null ? "My order" : c.Item2;
        }

        private static Tuple<string, string> T(string a, string b) { return Tuple.Create(a, b); }
    }

    public class SortInfo
    {
        public long Added;
        public int? Year;
        public double? Rating;
        public int Num;
        public string Name;
    }
}
