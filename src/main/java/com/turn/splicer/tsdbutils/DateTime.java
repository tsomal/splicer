/**
 * Copyright 2015-2016 The Splicer Query Engine Authors
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

package com.turn.splicer.tsdbutils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.TimeZone;

/**
 * Utility class that provides helpers for dealing with dates and timestamps.
 * In particular, this class handles parsing relative or human readable
 * date/time strings provided in queries.
 *
 * @since 2.0
 */
public class DateTime {

	/**
	 * Immutable cache mapping a timezone name to its object.
	 * We do this because the JDK's TimeZone class was implemented by retards,
	 * and it's synchronized, going through a huge pile of code, and allocating
	 * new objects all the time.  And to make things even better, if you ask for
	 * a TimeZone that doesn't exist, it returns GMT!  It is thus impractical to
	 * tell if the timezone name was valid or not.  JDK_brain_damage++;
	 * Note: caching everything wastes a few KB on RAM (34KB on my system with
	 * 611 timezones -- each instance is 56 bytes with the Sun JDK).
	 */
	public static final HashMap<String, TimeZone> timezones;

	static {
		final String[] tzs = TimeZone.getAvailableIDs();
		timezones = new HashMap<>(tzs.length);
		for (final String tz : tzs) {
			timezones.put(tz, TimeZone.getTimeZone(tz));
		}
	}

	/**
	 * Attempts to parse a timestamp from a given string
	 * Formats accepted are:
	 * <ul>
	 * <li>Relative: {@code 5m-ago}, {@code 1h-ago}, etc. See
	 * {@link #parseDuration}</li>
	 * <li>Absolute human readable dates:
	 * <ul><li>"yyyy/MM/dd-HH:mm:ss"</li>
	 * <li>"yyyy/MM/dd HH:mm:ss"</li>
	 * <li>"yyyy/MM/dd-HH:mm"</li>
	 * <li>"yyyy/MM/dd HH:mm"</li>
	 * <li>"yyyy/MM/dd"</li></ul></li>
	 * <li>Unix Timestamp in seconds or milliseconds:
	 * <ul><li>1355961600</li>
	 * <li>1355961600000</li>
	 * <li>1355961600.000</li></ul></li>
	 * </ul>
	 *
	 * @param datetime The string to parse a value for
	 * @return A Unix epoch timestamp in milliseconds
	 * @throws NullPointerException     if the timestamp is null
	 * @throws IllegalArgumentException if the request was malformed
	 */
	public static final long parseDateTimeString(final String datetime,
	                                             final String tz) {
		if (datetime == null || datetime.isEmpty())
			return -1;
		if (datetime.toLowerCase().endsWith("-ago")) {
			long interval = DateTime.parseDuration(
					datetime.substring(0, datetime.length() - 4));
			return System.currentTimeMillis() - interval;
		}

		if (datetime.contains("/") || datetime.contains(":")) {
			try {
				SimpleDateFormat fmt = null;
				switch (datetime.length()) {
					// these were pulled from cliQuery but don't work as intended since
					// they assume a date of 1970/01/01. Can be fixed but may not be worth
					// it
					// case 5:
					//   fmt = new SimpleDateFormat("HH:mm");
					//   break;
					// case 8:
					//   fmt = new SimpleDateFormat("HH:mm:ss");
					//   break;
					case 10:
						fmt = new SimpleDateFormat("yyyy/MM/dd");
						break;
					case 16:
						if (datetime.contains("-"))
							fmt = new SimpleDateFormat("yyyy/MM/dd-HH:mm");
						else
							fmt = new SimpleDateFormat("yyyy/MM/dd HH:mm");
						break;
					case 19:
						if (datetime.contains("-"))
							fmt = new SimpleDateFormat("yyyy/MM/dd-HH:mm:ss");
						else
							fmt = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
						break;
					default:
						// todo - deal with internationalization, other time formats
						throw new IllegalArgumentException("Invalid absolute date: "
								+ datetime);
				}
				if (tz != null && !tz.isEmpty())
					setTimeZone(fmt, tz);
				return fmt.parse(datetime).getTime();
			} catch (ParseException e) {
				throw new IllegalArgumentException("Invalid date: " + datetime
						+ ". " + e.getMessage());
			}
		} else {
			try {
				long time;
				if (datetime.contains(".")) {
					if (datetime.charAt(10) != '.' || datetime.length() != 14) {
						throw new IllegalArgumentException("Invalid time: " + datetime
								+ ". Millisecond timestamps must be in the format "
								+ "<seconds>.<ms> where the milliseconds are limited to 3 digits");
					}
					time = parseLong(datetime.replace(".", ""));
				} else {
					time = parseLong(datetime);
				}
				if (time < 0) {
					throw new IllegalArgumentException("Invalid time: " + datetime
							+ ". Negative timestamps are not supported.");
				}
				// this is a nasty hack to determine if the incoming request is
				// in seconds or milliseconds. This will work until November 2286
				if (datetime.length() <= 10)
					time *= 1000;
				return time;
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Invalid time: " + datetime
						+ ". " + e.getMessage());
			}
		}
	}

	/**
	 * Parses a human-readable duration (e.g, "10m", "3h", "14d") into seconds.
	 * <p/>
	 * Formats supported:<ul>
	 * <li>{@code ms}: milliseconds</li>
	 * <li>{@code s}: seconds</li>
	 * <li>{@code m}: minutes</li>
	 * <li>{@code h}: hours</li>
	 * <li>{@code d}: days</li>
	 * <li>{@code w}: weeks</li>
	 * <li>{@code n}: month (30 days)</li>
	 * <li>{@code y}: years (365 days)</li></ul>
	 *
	 * @param duration The human-readable duration to parse.
	 * @return A strictly positive number of milliseconds.
	 * @throws IllegalArgumentException if the interval was malformed.
	 */
	public static final long parseDuration(final String duration) {
		long interval;
		long multiplier;
		double temp;
		int unit = 0;
		while (Character.isDigit(duration.charAt(unit))) {
			unit++;
		}
		try {
			interval = Long.parseLong(duration.substring(0, unit));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid duration (number): " + duration);
		}
		if (interval <= 0) {
			throw new IllegalArgumentException("Zero or negative duration: " + duration);
		}
		switch (duration.toLowerCase().charAt(duration.length() - 1)) {
			case 's':
				if (duration.charAt(duration.length() - 2) == 'm') {
					return interval;
				}
				multiplier = 1;
				break;                        // seconds
			case 'm':
				multiplier = 60;
				break;               // minutes
			case 'h':
				multiplier = 3600;
				break;             // hours
			case 'd':
				multiplier = 3600 * 24;
				break;        // days
			case 'w':
				multiplier = 3600 * 24 * 7;
				break;    // weeks
			case 'n':
				multiplier = 3600 * 24 * 30;
				break;   // month (average)
			case 'y':
				multiplier = 3600 * 24 * 365;
				break;  // years (screw leap years)
			default:
				throw new IllegalArgumentException("Invalid duration (suffix): " + duration);
		}
		multiplier *= 1000;
		temp = (double) interval * multiplier;
		if (temp > Long.MAX_VALUE) {
			throw new IllegalArgumentException("Duration must be < Long.MAX_VALUE ms: " + duration);
		}
		return interval * multiplier;
	}

	/**
	 * Returns whether or not a date is specified in a relative fashion.
	 * <p/>
	 * A date is specified in a relative fashion if it ends in "-ago",
	 * e.g. {@code 1d-ago} is the same as {@code 24h-ago}.
	 *
	 * @param value The value to parse
	 * @return {@code true} if the parameter is passed and is a relative date.
	 * Note the method doesn't attempt to validate the relative date.  So this
	 * function can return true on something that looks like a relative date,
	 * but is actually invalid once we really try to parse it.
	 * @throws NullPointerException if the value is null
	 */
	public static boolean isRelativeDate(final String value) {
		return value.toLowerCase().endsWith("-ago");
	}

	/**
	 * Applies the given timezone to the given date format.
	 *
	 * @param fmt    Date format to apply the timezone to.
	 * @param tzname Name of the timezone, or {@code null} in which case this
	 *               function is a no-op.
	 * @throws IllegalArgumentException if tzname isn't a valid timezone name.
	 * @throws NullPointerException     if the format is null
	 */
	public static void setTimeZone(final SimpleDateFormat fmt,
	                               final String tzname) {
		if (tzname == null) {
			return;  // Use the default timezone.
		}
		final TimeZone tz = DateTime.timezones.get(tzname);
		if (tz != null) {
			fmt.setTimeZone(tz);
		} else {
			throw new IllegalArgumentException("Invalid timezone name: " + tzname);
		}
	}

	/**
	 * Sets the default timezone for this running OpenTSDB instance
	 * <p/>
	 * <b>WARNING</b> If OpenTSDB is used with a Security Manager, setting the default
	 * timezone only works for the running thread. Otherwise it will work for the
	 * entire application.
	 * <p/>
	 *
	 * @param tzname Name of the timezone to use
	 * @throws IllegalArgumentException if tzname isn't a valid timezone name
	 */
	public static void setDefaultTimezone(final String tzname) {
		final TimeZone tz = DateTime.timezones.get(tzname);
		if (tz != null) {
			TimeZone.setDefault(tz);
		} else {
			throw new IllegalArgumentException("Invalid timezone name: " + tzname);
		}
	}

	/**
	 * Parses an integer value as a long from the given character sequence.
	 * <p/>
	 * This is equivalent to {@link Long#parseLong(String)} except it's up to
	 * 100% faster on {@link String} and always works in O(1) space even with
	 * {@link StringBuilder} buffers (where it's 2x to 5x faster).
	 *
	 * @param s The character sequence containing the integer value to parse.
	 * @return The value parsed.
	 * @throws NumberFormatException if the value is malformed or overflows.
	 */
	public static long parseLong(final CharSequence s) {
		final int n = s.length();  // Will NPE if necessary.
		if (n == 0) {
			throw new NumberFormatException("Empty string");
		}
		char c = s.charAt(0);  // Current character.
		int i = 1;  // index in `s'.
		if (c < '0' && (c == '+' || c == '-')) {  // Only 1 test in common case.
			if (n == 1) {
				throw new NumberFormatException("Just a sign, no value: " + s);
			} else if (n > 20) {  // "+9223372036854775807" or "-9223372036854775808"
				throw new NumberFormatException("Value too long: " + s);
			}
			c = s.charAt(1);
			i = 2;  // Skip over the sign.
		} else if (n > 19) {  // "9223372036854775807"
			throw new NumberFormatException("Value too long: " + s);
		}
		long v = 0;  // The result (negated to easily handle MIN_VALUE).
		do {
			if ('0' <= c && c <= '9') {
				v -= c - '0';
			} else {
				throw new NumberFormatException("Invalid character '" + c
						+ "' in " + s);
			}
			if (i == n) {
				break;
			}
			v *= 10;
			c = s.charAt(i++);
		} while (true);
		if (v > 0) {
			throw new NumberFormatException("Overflow in " + s);
		} else if (s.charAt(0) == '-') {
			return v;  // Value is already negative, return unchanged.
		} else if (v == Long.MIN_VALUE) {
			throw new NumberFormatException("Overflow in " + s);
		} else {
			return -v;  // Positive value, need to fix the sign.
		}
	}
}
