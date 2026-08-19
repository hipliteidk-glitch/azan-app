/**
 * Offline prayer-times engine for the WebView.
 *
 * The bundled UI talks to api.aladhan.com. That call fails inside
 * file:// WebView (CORS + swapped /calendar/{month}/{year} path).
 * We answer those fetches locally so times always load.
 */
(function () {
  if (window.__azanPrayerEngine) return;
  window.__azanPrayerEngine = true;

  var D2R = Math.PI / 180;
  var R2D = 180 / Math.PI;
  var KAABA = { lat: 21.4225, lng: 39.8262 };

  var WEEKDAYS = [
    "Sunday",
    "Monday",
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday"
  ];

  var HIJRI_EN = [
    "Muharram",
    "Safar",
    "Rabi al-Awwal",
    "Rabi al-Thani",
    "Jumada al-Ula",
    "Jumada al-Akhira",
    "Rajab",
    "Shaban",
    "Ramadan",
    "Shawwal",
    "Dhu al-Qadah",
    "Dhu al-Hijjah"
  ];

  var HIJRI_AR = [
    "محرم",
    "صفر",
    "ربيع الأول",
    "ربيع الآخر",
    "جمادى الأولى",
    "جمادى الآخرة",
    "رجب",
    "شعبان",
    "رمضان",
    "شوال",
    "ذو القعدة",
    "ذو الحجة"
  ];

  // Official Aladhan method ids.
  var METHODS = {
    0: { fajr: 16, isha: 14, maghrib: 4, asr: 1 },
    1: { fajr: 18, isha: 18, asr: 1 },
    2: { fajr: 15, isha: 15, asr: 1 },
    3: { fajr: 18, isha: 17, asr: 1 },
    4: { fajr: 18.5, ishaMin: 90, asr: 1 },
    5: { fajr: 19.5, isha: 17.5, asr: 1 },
    7: { fajr: 17.7, isha: 14, maghrib: 4.5, asr: 1 },
    8: { fajr: 19.5, ishaMin: 90, asr: 1 },
    9: { fajr: 18, isha: 17.5, asr: 1 },
    10: { fajr: 18, ishaMin: 90, asr: 1 },
    11: { fajr: 20, isha: 18, asr: 1 },
    12: { fajr: 12, isha: 12, asr: 1 },
    13: { fajr: 18, isha: 17, asr: 1 },
    14: { fajr: 16, isha: 15, asr: 1 },
    15: { fajr: 18, isha: 18, asr: 1 },
    16: { fajr: 18.2, isha: 18.2, asr: 1 },
    17: { fajr: 20, isha: 18, asr: 1 }
  };

  var CITIES = [
    { keys: ["makkah", "mecca", "makkah al mukarramah"], lat: 21.4225, lng: 39.8262, tz: 3, method: 4, label: "Makkah, Saudi Arabia" },
    { keys: ["madinah", "medina", "al madinah"], lat: 24.4672, lng: 39.6111, tz: 3, method: 4, label: "Madinah, Saudi Arabia" },
    { keys: ["kuala lumpur", "kl", "kualalumpur"], lat: 3.139, lng: 101.6869, tz: 8, method: 3, label: "Kuala Lumpur, Malaysia" },
    { keys: ["shah alam"], lat: 3.0733, lng: 101.5185, tz: 8, method: 3, label: "Shah Alam, Malaysia" },
    { keys: ["johor bahru"], lat: 1.4927, lng: 103.7414, tz: 8, method: 3, label: "Johor Bahru, Malaysia" },
    { keys: ["penang", "george town"], lat: 5.4141, lng: 100.3288, tz: 8, method: 3, label: "George Town, Malaysia" },
    { keys: ["singapore"], lat: 1.3521, lng: 103.8198, tz: 8, method: 11, label: "Singapore" },
    { keys: ["jakarta"], lat: -6.2088, lng: 106.8456, tz: 7, method: 3, label: "Jakarta, Indonesia" },
    { keys: ["istanbul"], lat: 41.0082, lng: 28.9784, tz: 3, method: 13, label: "Istanbul, Türkiye" },
    { keys: ["london"], lat: 51.5074, lng: -0.1278, tz: 1, method: 3, label: "London, United Kingdom" },
    { keys: ["cairo"], lat: 30.0444, lng: 31.2357, tz: 2, method: 5, label: "Cairo, Egypt" },
    { keys: ["new york"], lat: 40.7128, lng: -74.006, tz: -4, method: 2, label: "New York, United States" },
    { keys: ["dubai"], lat: 25.2048, lng: 55.2708, tz: 4, method: 8, label: "Dubai, UAE" },
    { keys: ["karachi"], lat: 24.8607, lng: 67.0011, tz: 5, method: 1, label: "Karachi, Pakistan" },
    { keys: ["istanbul"], lat: 41.0082, lng: 28.9784, tz: 3, method: 13, label: "Istanbul, Türkiye" }
  ];

  function pad(n) {
    return String(n).padStart(2, "0");
  }

  function norm(s) {
    return String(s || "")
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, " ")
      .trim();
  }

  function findCity(city, country) {
    var q = norm(city);
    if (!q) return null;
    var hit = null;
    for (var i = 0; i < CITIES.length; i++) {
      var c = CITIES[i];
      for (var k = 0; k < c.keys.length; k++) {
        if (q === c.keys[k] || q.indexOf(c.keys[k]) !== -1 || c.keys[k].indexOf(q) !== -1) {
          hit = c;
          break;
        }
      }
      if (hit) break;
    }
    return hit;
  }

  function timezoneFor(lat, lng, cityInfo) {
    if (cityInfo && typeof cityInfo.tz === "number") return cityInfo.tz;
    if (Math.abs(lat - KAABA.lat) < 0.2 && Math.abs(lng - KAABA.lng) < 0.2) return 3;
    if (lat > 0.5 && lat < 7.5 && lng > 99.5 && lng < 104.8) return 8;
    return Math.round(lng / 15);
  }

  function julian(year, month, day) {
    if (month <= 2) {
      year -= 1;
      month += 12;
    }
    var A = Math.floor(year / 100);
    var B = 2 - A + Math.floor(A / 4);
    return Math.floor(365.25 * (year + 4716)) + Math.floor(30.6001 * (month + 1)) + day + B - 1524.5;
  }

  function sunPosition(jd) {
    var D = jd - 2451545.0;
    var g = (357.529 + 0.98560028 * D) % 360;
    var q = (280.459 + 0.98564736 * D) % 360;
    var L = (q + 1.915 * Math.sin(g * D2R) + 0.02 * Math.sin(2 * g * D2R)) % 360;
    var e = 23.439 - 0.00000036 * D;
    var RA = (R2D * Math.atan2(Math.cos(e * D2R) * Math.sin(L * D2R), Math.cos(L * D2R))) / 15;
    var eqt = q / 15 - (RA < 0 ? RA + 24 : RA);
    var decl = R2D * Math.asin(Math.sin(e * D2R) * Math.sin(L * D2R));
    return { decl: decl, eqt: eqt };
  }

  function hourAngle(angle, lat, decl) {
    var a = -Math.sin(angle * D2R) - Math.sin(lat * D2R) * Math.sin(decl * D2R);
    var b = Math.cos(lat * D2R) * Math.cos(decl * D2R);
    if (b === 0) return null;
    var c = a / b;
    if (c < -1) c = -1;
    if (c > 1) c = 1;
    return (1 / 15) * R2D * Math.acos(c);
  }

  function asrOffset(factor, lat, decl) {
    var angle = -R2D * Math.atan2(1, factor + Math.tan(Math.abs(lat - decl) * D2R));
    return hourAngle(angle, lat, decl);
  }

  function fixHour(h) {
    h = h % 24;
    if (h < 0) h += 24;
    return h;
  }

  function formatTime(hours) {
    if (hours == null || !isFinite(hours)) return null;
    hours = fixHour(hours + 0.5 / 60);
    var h = Math.floor(hours);
    var m = Math.floor((hours - h) * 60);
    return pad(h) + ":" + pad(m);
  }

  function computeDay(lat, lng, tz, year, month, day, methodId) {
    var method = METHODS[methodId] || METHODS[3];
    var jd = julian(year, month, day) - lng / (15 * 24);
    var sun = sunPosition(jd);
    var dhuhr = fixHour(12 + tz - lng / 15 - sun.eqt);
    var riseSet = hourAngle(0.833, lat, sun.decl);
    var sunrise = riseSet == null ? null : fixHour(dhuhr - riseSet);
    var sunset = riseSet == null ? null : fixHour(dhuhr + riseSet);
    var fajrH = hourAngle(method.fajr, lat, sun.decl);
    var fajr = fajrH == null ? null : fixHour(dhuhr - fajrH);
    var maghrib = sunset;
    if (method.maghrib) {
      var magH = hourAngle(method.maghrib, lat, sun.decl);
      if (magH != null) maghrib = fixHour(dhuhr + magH);
    }
    var isha;
    if (method.ishaMin != null && maghrib != null) {
      isha = fixHour(maghrib + method.ishaMin / 60);
    } else {
      var ishaH = hourAngle(method.isha, lat, sun.decl);
      isha = ishaH == null ? null : fixHour(dhuhr + ishaH);
    }
    var asrH = asrOffset(method.asr || 1, lat, sun.decl);
    var asr = asrH == null ? null : fixHour(dhuhr + asrH);
    return {
      Fajr: formatTime(fajr),
      Sunrise: formatTime(sunrise),
      Dhuhr: formatTime(dhuhr),
      Asr: formatTime(asr),
      Maghrib: formatTime(maghrib),
      Isha: formatTime(isha)
    };
  }

  function qibla(lat, lng) {
    var φ1 = lat * D2R;
    var φ2 = KAABA.lat * D2R;
    var Δλ = (KAABA.lng - lng) * D2R;
    var y = Math.sin(Δλ);
    var x = Math.cos(φ1) * Math.tan(φ2) - Math.sin(φ1) * Math.cos(Δλ);
    var b = Math.atan2(y, x) * R2D;
    return (b + 360) % 360;
  }

  // Tabular Islamic calendar (Kuwaiti algorithm).
  function gregorianToHijri(year, month, day) {
    var jd =
      Math.floor((1461 * (year + 4800 + Math.floor((month - 14) / 12))) / 4) +
      Math.floor((367 * (month - 2 - 12 * Math.floor((month - 14) / 12))) / 12) -
      Math.floor((3 * Math.floor((year + 4900 + Math.floor((month - 14) / 12)) / 100)) / 4) +
      day -
      32075;
    var l = jd - 1948440 + 10632;
    var n = Math.floor((l - 1) / 10631);
    l = l - 10631 * n + 354;
    var j =
      Math.floor((10985 - l) / 5316) * Math.floor((50 * l) / 17719) +
      Math.floor(l / 5670) * Math.floor((43 * l) / 15238);
    l =
      l -
      Math.floor((30 - j) / 15) * Math.floor((17719 * j) / 50) -
      Math.floor(j / 16) * Math.floor((15238 * j) / 43) +
      29;
    var hm = Math.floor((24 * l) / 709);
    var hd = l - Math.floor((709 * hm) / 24);
    var hy = 30 * n + j - 30;
    return { year: hy, month: hm, day: hd };
  }

  function daysInMonth(year, month) {
    return new Date(year, month, 0).getDate();
  }

  function weekdayName(year, month, day) {
    return WEEKDAYS[new Date(year, month - 1, day).getDay()];
  }

  function buildDayPayload(lat, lng, tz, year, month, day, methodId) {
    var timings = computeDay(lat, lng, tz, year, month, day, methodId);
    var hijri = gregorianToHijri(year, month, day);
    var wd = weekdayName(year, month, day);
    var iso = year + "-" + pad(month) + "-" + pad(day);
    var hMonth = Math.max(1, Math.min(12, hijri.month));
    return {
      timings: timings,
      date: {
        readable: pad(day) + " " + iso,
        gregorian: {
          date: iso,
          format: "YYYY-MM-DD",
          day: pad(day),
          weekday: { en: wd },
          month: { number: month, en: iso.slice(5, 7) },
          year: String(year)
        },
        hijri: {
          date: pad(hijri.day) + "-" + pad(hMonth) + "-" + hijri.year,
          day: String(hijri.day),
          month: { number: hMonth, en: HIJRI_EN[hMonth - 1], ar: HIJRI_AR[hMonth - 1] },
          year: String(hijri.year)
        }
      },
      meta: { latitude: lat, longitude: lng, timezone: tz, method: { id: methodId }, qibla: qibla(lat, lng) }
    };
  }

  function parseYearMonth(a, b) {
    var x = Number(a);
    var y = Number(b);
    if (!isFinite(x) || !isFinite(y)) return null;
    if (x > 31) return { year: x, month: y };
    if (y > 31) return { year: y, month: x };
    return { year: x, month: y };
  }

  function resolvePlace(params) {
    var lat = params.get("latitude");
    var lng = params.get("longitude");
    var city = params.get("city");
    var country = params.get("country");
    var cityInfo = city ? findCity(city, country) : null;
    if (lat != null && lat !== "" && lng != null && lng !== "") {
      lat = Number(lat);
      lng = Number(lng);
      if (!isFinite(lat) || !isFinite(lng)) return null;
      return { lat: lat, lng: lng, tz: timezoneFor(lat, lng, cityInfo), cityInfo: cityInfo };
    }
    if (cityInfo) {
      return { lat: cityInfo.lat, lng: cityInfo.lng, tz: cityInfo.tz, cityInfo: cityInfo };
    }
    return { lat: KAABA.lat, lng: KAABA.lng, tz: 3, cityInfo: CITIES[0] };
  }

  function handleAladhan(rawUrl) {
    var url = new URL(rawUrl, "https://api.aladhan.com");
    var parts = url.pathname.split("/").filter(Boolean);
    var params = url.searchParams;
    var methodId = Number(params.get("method") || 3);
    if (!METHODS[methodId]) methodId = 3;
    var place = resolvePlace(params);
    if (!place) throw new Error("Missing location");

    var year;
    var month;
    var day = null;
    var isCalendar = parts.indexOf("calendar") !== -1 || parts.indexOf("calendarByCity") !== -1;
    var isTimings = parts.indexOf("timings") !== -1 || parts.indexOf("timingsByCity") !== -1;

    if (parts.length >= 4 && (parts[1] === "calendar" || parts[1] === "calendarByCity")) {
      var ym = parseYearMonth(parts[2], parts[3]);
      if (ym) {
        year = ym.year;
        month = ym.month;
      }
    }
    if (year == null) {
      year = Number(params.get("year") || new Date().getFullYear());
      month = Number(params.get("month") || new Date().getMonth() + 1);
    }
    if (params.get("date")) {
      var bits = params.get("date").split("-");
      if (bits.length === 3) {
        if (bits[0].length === 4) {
          year = Number(bits[0]);
          month = Number(bits[1]);
          day = Number(bits[2]);
        } else {
          day = Number(bits[0]);
          month = Number(bits[1]);
          year = Number(bits[2]);
        }
      }
    }

    if (isTimings && day == null) {
      var now = new Date();
      year = now.getFullYear();
      month = now.getMonth() + 1;
      day = now.getDate();
    }

    if (isTimings) {
      var one = buildDayPayload(place.lat, place.lng, place.tz, year, month, day, methodId);
      return { code: 200, status: "OK", data: one };
    }

    var last = daysInMonth(year, month);
    var data = [];
    for (var d = 1; d <= last; d++) {
      data.push(buildDayPayload(place.lat, place.lng, place.tz, year, month, d, methodId));
    }
    return { code: 200, status: "OK", data: data };
  }

  function jsonResponse(obj) {
    var body = JSON.stringify(obj);
    if (typeof Response === "function") {
      return new Response(body, {
        status: 200,
        headers: {
          "Content-Type": "application/json",
          "Access-Control-Allow-Origin": "*"
        }
      });
    }
    return {
      ok: true,
      status: 200,
      json: function () {
        return Promise.resolve(obj);
      },
      text: function () {
        return Promise.resolve(body);
      }
    };
  }

  // Do not seed settings — the UI / GPS should choose the place.

  if (navigator.geolocation && !navigator.geolocation.__azanWrapped) {
    navigator.geolocation.__azanWrapped = true;
    var originalGetCurrentPosition = navigator.geolocation.getCurrentPosition.bind(navigator.geolocation);
    var meccaFix = {
      coords: {
        latitude: KAABA.lat,
        longitude: KAABA.lng,
        accuracy: 5000,
        altitude: null,
        altitudeAccuracy: null,
        heading: null,
        speed: null
      },
      timestamp: Date.now()
    };
    navigator.geolocation.getCurrentPosition = function (success, error, options) {
      try {
        originalGetCurrentPosition(
          function (pos) {
            if (typeof success === "function") success(pos);
          },
          function () {
            if (typeof success === "function") success(meccaFix);
          },
          options || { timeout: 8000, maximumAge: 60000 }
        );
      } catch (e) {
        if (typeof success === "function") success(meccaFix);
      }
    };
    navigator.geolocation.watchPosition = function (success, error, options) {
      navigator.geolocation.getCurrentPosition(success, error, options);
      return 1;
    };
  }

  var originalFetch = window.fetch.bind(window);
  window.fetch = function (input, init) {
    var url = typeof input === "string" ? input : input && input.url;
    if (url && /aladhan\.com/i.test(url)) {
      try {
        return Promise.resolve(jsonResponse(handleAladhan(url)));
      } catch (err) {
        console.error("Azan local engine error", err);
        return Promise.reject(err);
      }
    }
    return originalFetch(input, init);
  };

  window.__azanHandleAladhan = handleAladhan;
  window.__azanComputeDay = computeDay;
  console.log("Azan local prayer engine installed");
})();
