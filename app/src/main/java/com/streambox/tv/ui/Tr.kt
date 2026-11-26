package com.streambox.tv.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.text.Spanned
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatViewInflater
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatCheckedTextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.os.LocaleListCompat
import com.streambox.tv.R
import com.streambox.tv.data.Prefs

/**
 * Arabic interface without touching every screen: every TextView/Button the app inflates
 * translates its own text (exact phrases, phrases with values, and "a  •  b" style combinations).
 */
object Tr {
    @Volatile
    var enabled = false
        private set

    private val plain: Map<String, String> = hashMapOf(
        "Add profile" to "إضافة ملف",
        "+ Add profile" to "+ إضافة ملف",
        "+  My list" to "+  قائمتي",
        "+ My list" to "+ قائمتي",
        "2 screens" to "شاشتان",
        "4 screens" to "4 شاشات",
        "Add to favourites" to "إضافة إلى المفضلة",
        "Added to favourites" to "أُضيفت إلى المفضلة",
        "All channels" to "كل القنوات",
        "All movies" to "كل الأفلام",
        "All series" to "كل المسلسلات",
        "Allow StreamBox to install apps, then choose Install now again" to "اسمح لـ StreamBox بتثبيت التطبيقات، ثم اختر «تثبيت الآن» مرة أخرى",
        "Always highest" to "الأعلى دائمًا",
        "Always highest quality" to "أعلى جودة دائمًا",
        "Audio track" to "مسار الصوت",
        "Auto" to "تلقائي",
        "Auto (adapts to your connection)" to "تلقائي (حسب سرعة الاتصال)",
        "Auto (starts high, adapts to your connection)" to "تلقائي (يبدأ بأعلى جودة ويتكيّف مع الاتصال)",
        "Back = return" to "رجوع = العودة",
        "Built-in player" to "المشغّل المدمج",
        "Cache cleared" to "تم مسح الذاكرة المؤقتة",
        "Cancel" to "إلغاء",
        "Backup & restore" to "النسخ الاحتياطي والاستعادة",
        "Save everything (logins, viewers, favourites, settings) to your phone or a USB drive, and restore it on any TV" to "احفظ كل شيء (بيانات الدخول، المشاهدين، المفضلة، الإعدادات) على هاتفك أو ذاكرة USB واستعده على أي تلفاز",
        "📱  Back up to my phone" to "📱  نسخ احتياطي إلى هاتفي", "📱  Restore from my phone" to "📱  استعادة من هاتفي",
        "💾  Save backup on this TV or a USB drive" to "💾  حفظ نسخة على هذا التلفاز أو ذاكرة USB",
        "💾  Restore from a file on this TV or USB" to "💾  استعادة من ملف على هذا التلفاز أو USB",
        "Back up to my phone" to "نسخ احتياطي إلى هاتفي", "Restore from my phone" to "استعادة من هاتفي",
        "Choose a backup password (4+ characters)" to "اختر كلمة مرور للنسخة (4 أحرف أو أكثر)",
        "Repeat the backup password" to "أعد كلمة مرور النسخة", "Backup password" to "كلمة مرور النسخة الاحتياطية",
        "Use at least 4 characters" to "استخدم 4 أحرف على الأقل", "Passwords do not match" to "كلمتا المرور غير متطابقتين",
        "Preparing backup…" to "جارٍ تجهيز النسخة…", "Restoring…" to "جارٍ الاستعادة…", "Backup restored" to "تمت استعادة النسخة",
        "Backup saved" to "تم حفظ النسخة", "Couldn't restore" to "تعذّرت الاستعادة", "Choose a backup" to "اختر نسخة احتياطية",
        "Wrong password, or the file is not a StreamBox backup." to "كلمة مرور خاطئة، أو الملف ليس نسخة StreamBox.",
        "No backup files found on this TV or USB drives" to "لا توجد نسخ احتياطية على التلفاز أو ذاكرة USB",
        "Connect the TV to your home network first" to "صِل التلفاز بشبكة المنزل أولًا", "Save to" to "الحفظ إلى",
        "Viewers" to "المشاهدون", "Who's watching?" to "من يشاهد؟", "👤  Switch viewer" to "👤  تبديل المشاهد",
        "⇄  Switch provider profile" to "⇄  تبديل ملف المزوّد", "Your name" to "اسمك", "Viewer name" to "اسم المشاهد",
        "Kids viewer name" to "اسم مشاهد الأطفال", "+  Add viewer" to "+  إضافة مشاهد", "+  Add Kids viewer" to "+  إضافة مشاهد أطفال",
        "Rename" to "إعادة تسمية", "Make a normal viewer" to "جعله مشاهدًا عاديًا", "Make a Kids viewer" to "جعله مشاهد أطفال",
        "Add viewers in Settings → Viewers" to "أضف مشاهدين من الإعدادات ← المشاهدون",
        "Tip: set a parental PIN so kids can't leave their profile" to "نصيحة: اضبط رقمًا سريًا حتى لا يخرج الأطفال من ملفهم",
        "🧒  Show in Kids profiles" to "🧒  إظهار في ملفات الأطفال", "🧒  Remove from Kids profiles" to "🧒  إزالة من ملفات الأطفال",
        "Removed from Kids profiles" to "أُزيلت من ملفات الأطفال",
        "▲  Move up" to "▲  نقل للأعلى", "▼  Move down" to "▼  نقل للأسفل", "Hide this category" to "إخفاء هذه الفئة",
        "Hidden. Show it again in Settings → Channel manager" to "تم الإخفاء. أظهره مجددًا من الإعدادات ← إدارة القنوات",
        "Rename channel" to "إعادة تسمية القناة", "Hide channel" to "إخفاء القناة", "Rename channel (empty = original name)" to "إعادة تسمية القناة (فارغ = الاسم الأصلي)",
        "Channel manager" to "إدارة القنوات", "Sort channels" to "ترتيب القنوات", "Provider order" to "ترتيب المزوّد", "A–Z" to "أبجديًا",
        "Channel number" to "رقم القناة", "Undo all renames, hiding and ordering" to "التراجع عن كل التسميات والإخفاء والترتيب",
        "Show a hidden channel again" to "إظهار قناة مخفية", "Show a hidden category again" to "إظهار فئة مخفية",
        "Undo all channel changes?" to "التراجع عن كل تغييرات القنوات؟", "Undo all" to "تراجع عن الكل", "Open a profile first" to "افتح ملفًا أولًا",
        "Sort channels, show hidden channels and categories, undo renames" to "ترتيب القنوات، إظهار القنوات والفئات المخفية، التراجع عن التسميات",
        "Audio language" to "لغة الصوت", "Stream default" to "افتراضي البث", "Channel group" to "مجموعة القنوات",
        "◀ change group   •   OK watch   •   Back close" to "◀ تغيير المجموعة   •   OK مشاهدة   •   رجوع إغلاق",
        "OK channel list   ▲▼ or 0-9 change channel   ⏯ pause   ◀▶ menu   INFO details" to "OK قائمة القنوات   ▲▼ أو 0-9 تغيير القناة   ⏯ إيقاف   ◀▶ القائمة   INFO التفاصيل",
        "About & diagnostics" to "حول التطبيق والتشخيص",
        "Version, connection test, subscription status, problem reports, privacy" to "الإصدار، فحص الاتصال، حالة الاشتراك، تقارير المشاكل، الخصوصية",
        "Version" to "الإصدار", "Device" to "الجهاز", "Internet" to "الإنترنت", "Provider server" to "خادم المزوّد",
        "Subscription" to "الاشتراك", "Free storage" to "المساحة المتاحة", "Problem report" to "تقرير المشكلة",
        "Privacy" to "الخصوصية", "Open-source licences" to "تراخيص المصادر المفتوحة", "Press OK to view" to "اضغط OK للعرض",
        "Press OK to read how your data is handled" to "اضغط OK لمعرفة كيفية التعامل مع بياناتك",
        "No crashes recorded" to "لم تُسجَّل أي أعطال", "Checking…" to "جارٍ الفحص…", "Offline or blocked" to "غير متصل أو محجوب",
        "Not reachable right now" to "لا يمكن الوصول إليه حاليًا", "No server (playlist profile)" to "لا يوجد خادم (ملف قائمة تشغيل)",
        "A report from the last crash is saved. Press OK to send it" to "تم حفظ تقرير آخر عطل. اضغط OK لإرساله",
        "StreamBox closed unexpectedly" to "أُغلق StreamBox بشكل غير متوقع",
        "A problem report was saved. Send it so the problem can be fixed? Your logins are not included." to "تم حفظ تقرير بالمشكلة. هل تريد إرساله لإصلاحها؟ لا يتضمن بيانات الدخول.",
        "Send report" to "إرسال التقرير", "Not now" to "ليس الآن", "Send problem report" to "إرسال تقرير المشكلة",
        "Scan with your phone to send the report:" to "امسح بالهاتف لإرسال التقرير:",
        "▶ Continue watching" to "▶ متابعة المشاهدة",
        "Remove from Continue watching" to "إزالة من متابعة المشاهدة",
        "Install with Downloader" to "التثبيت عبر Downloader",
        "Open Downloader" to "فتح Downloader",
        "Downloader isn't installed" to "تطبيق Downloader غير مثبّت",
        "Install the update" to "تثبيت التحديث",
        "Open settings" to "فتح الإعدادات",
        "Profile password" to "كلمة مرور الملف",
        "Wrong password" to "كلمة المرور خاطئة",
        "Built-in profile  •  🔒 login hidden" to "ملف مدمج  •  🔒 بيانات الدخول مخفية",
        "Choose a programme to watch, record or set a reminder" to "اختر برنامجًا للمشاهدة أو التسجيل أو التذكير",
        "This programme has already finished" to "انتهى هذا البرنامج",
        "Back to live" to "العودة إلى المباشر",
        "Rewind isn't available on this channel" to "الإرجاع غير متاح لهذه القناة",
        "Cast" to "طاقم التمثيل",
        "Catch-up" to "إعادة البث",
        "Catch-up available" to "إعادة البث متاحة",
        "Change PIN" to "تغيير الرقم السري",
        "Channel lists and TV guide are downloaded again next time" to "سيُعاد تنزيل القنوات ودليل البرامج في المرة القادمة",
        "Channel preview in Live TV" to "معاينة القناة في البث المباشر",
        "Channels, movies, series…" to "قنوات، أفلام، مسلسلات…",
        "Check for updates" to "البحث عن تحديثات",
        "Check your connection, then press Refresh (top right) to try again." to "تحقّق من الاتصال ثم اضغط «تحديث» للمحاولة مرة أخرى.",
        "Checking login…" to "جارٍ التحقق من تسجيل الدخول…",
        "Checking the stream format…" to "جارٍ فحص صيغة البث…",
        "Choose a group" to "اختر مجموعة",
        "Choose a profile" to "اختر ملفًا",
        "Choose subtitles" to "اختر الترجمة",
        "Clear cached data" to "مسح البيانات المؤقتة",
        "Clear resume positions" to "مسح مواضع الاستئناف",
        "Connect the TV to Wi-Fi or Ethernet to add a profile from your phone." to "صِل التلفاز بشبكة Wi-Fi أو بكابل لإضافة ملف من هاتفك.",
        "Content refreshed" to "تم تحديث المحتوى",
        "Continue watching" to "متابعة المشاهدة",
        "Couldn't load" to "تعذّر التحميل",
        "Couldn't reach GitHub to check for updates" to "تعذّر الوصول إلى GitHub للتحقق من التحديثات",
        "Custom…" to "مخصّص…",
        "Cyan" to "سماوي",
        "Dark box" to "صندوق داكن",
        "Default" to "افتراضي",
        "Delete" to "حذف",
        "Details received from your phone" to "تم استلام البيانات من هاتفك",
        "Dismiss" to "تجاهل",
        "Done" to "تم",
        "Downloading playlist…" to "جارٍ تنزيل قائمة التشغيل…",
        "Downloading update…" to "جارٍ تنزيل التحديث…",
        "EPG URL (XMLTV, optional)" to "رابط دليل البرامج (XMLTV، اختياري)",
        "Enter a playlist URL starting with http:// or https://" to "أدخل رابط قائمة يبدأ بـ http:// أو https://",
        "Enter parental PIN" to "أدخل الرقم السري للرقابة الأبوية",
        "External player app (VLC, MX Player…)" to "مشغّل خارجي (VLC، MX Player…)",
        "Extra large" to "كبير جدًا",
        "Failed" to "فشل",
        "Favourite channels" to "القنوات المفضلة",
        "Find subtitles online (OpenSubtitles)" to "البحث عن ترجمة على الإنترنت (OpenSubtitles)",
        "Fit" to "ملاءمة",
        "Forget where you stopped in movies and episodes" to "نسيان مواضع التوقف في الأفلام والحلقات",
        "Highest" to "الأعلى",
        "Hold OK on a profile to delete it. This app is a player only and contains no channels." to "اضغط مطوّلًا على OK فوق الملف لحذفه. هذا التطبيق مشغّل فقط ولا يحتوي على قنوات.",
        "Install now" to "تثبيت الآن",
        "Install the YouTube app to watch trailers" to "ثبّت تطبيق YouTube لمشاهدة الإعلانات",
        "Interrupted" to "انقطع",
        "Its favourites and locks are removed too." to "ستُحذف المفضلة والأقفال الخاصة به أيضًا.",
        "Keep typing…" to "تابع الكتابة…",
        "LIVE" to "مباشر",
        "Large" to "كبير",
        "Later" to "لاحقًا",
        "Live TV" to "البث المباشر",
        "Live channels" to "القنوات المباشرة",
        "Live stream format" to "صيغة البث المباشر",
        "Loading episodes…" to "جارٍ تحميل الحلقات…",
        "Loading guide…" to "جارٍ تحميل الدليل…",
        "Loading live channels…" to "جارٍ تحميل القنوات…",
        "Loading movies…" to "جارٍ تحميل الأفلام…",
        "Loading series…" to "جارٍ تحميل المسلسلات…",
        "Loading your channels, movies and series…" to "جارٍ تحميل القنوات والأفلام والمسلسلات…",
        "Lock this category with PIN" to "قفل هذه الفئة بالرقم السري",
        "Login failed: no account info returned" to "فشل تسجيل الدخول: لم تُرجَع بيانات الحساب",
        "M3U playlist URL" to "رابط قائمة M3U",
        "Mono" to "أحادي",
        "Movie" to "فيلم",
        "Movies" to "الأفلام",
        "Multi-screen" to "تعدّد الشاشات",
        "My list" to "قائمتي",
        "My playlist" to "قائمتي",
        "New PIN (4 digits or more)" to "رقم سري جديد (4 أرقام أو أكثر)",
        "New movies" to "أفلام جديدة",
        "New series" to "مسلسلات جديدة",
        "No TV guide for this playlist" to "لا يوجد دليل برامج لهذه القائمة",
        "No alternative audio tracks" to "لا توجد مسارات صوت أخرى",
        "No episodes found" to "لم يتم العثور على حلقات",
        "No external player found, using the built-in one" to "لا يوجد مشغّل خارجي، سيُستخدم المشغّل المدمج",
        "No external player installed. Install VLC or MX Player from the Play Store." to "لا يوجد مشغّل خارجي. ثبّت VLC أو MX Player من متجر Play.",
        "No previous channel yet" to "لا توجد قناة سابقة بعد",
        "No profiles yet. Add your provider's Xtream Codes login or an M3U playlist URL to begin." to "لا توجد ملفات بعد. أضف بيانات Xtream Codes من مزوّدك أو رابط قائمة M3U للبدء.",
        "No programme information" to "لا توجد معلومات عن البرنامج",
        "No programme information for this channel" to "لا توجد معلومات برامج لهذه القناة",
        "No recordings yet. Record from the player menu (◀▶ on live TV) or from a channel's guide." to "لا توجد تسجيلات بعد. سجّل من قائمة المشغّل (◀▶ في البث المباشر) أو من دليل القناة.",
        "No subtitles found for this title" to "لم يتم العثور على ترجمة لهذا العنوان",
        "Normal" to "عادي",
        "Not available in the catch-up archive" to "غير متاح في أرشيف إعادة البث",
        "Not set. Free key from opensubtitles.com → API consumers; enables “Find subtitles online”" to "غير مضبوط. مفتاح مجاني من opensubtitles.com ← API consumers، يفعّل «البحث عن ترجمة على الإنترنت»",
        "Not set. Free key from themoviedb.org → Settings → API; adds HD backdrops, cast photos and trailers" to "غير مضبوط. مفتاح مجاني من themoviedb.org ← Settings ← API، يضيف خلفيات عالية الدقة وصور الممثلين والإعلانات",
        "Not supported on this TV" to "غير مدعوم على هذا التلفاز",
        "Nothing here" to "لا يوجد شيء هنا",
        "Off" to "إيقاف",
        "Off. Set a PIN to lock categories" to "متوقف. اضبط رقمًا سريًا لقفل الفئات",
        "Off: OK opens full screen" to "متوقف: OK يفتح ملء الشاشة",
        "Off: always show the profile list" to "متوقف: عرض قائمة الملفات دائمًا",
        "Offline" to "غير متصل",
        "On: first OK previews, second OK goes full screen" to "مفعّل: أول OK للمعاينة، والثاني لملء الشاشة",
        "On: keep watching in a small window" to "مفعّل: تابع المشاهدة في نافذة صغيرة",
        "On: reopen the last profile, screen and channel" to "مفعّل: إعادة فتح آخر ملف وشاشة وقناة",
        "OpenSubtitles API key" to "مفتاح OpenSubtitles API",
        "Or type on your phone" to "أو اكتب من هاتفك",
        "Outline" to "حدود",
        "PIN is set. Adult categories and locked categories ask for it" to "الرقم السري مضبوط. فئات البالغين والفئات المقفلة تطلبه",
        "PIN must have at least 4 digits" to "يجب أن يتكوّن الرقم السري من 4 أرقام على الأقل",
        "PINs do not match" to "الرقمان غير متطابقين",
        "Parental PIN set" to "تم ضبط الرقم السري",
        "Parental control" to "الرقابة الأبوية",
        "Parental control is off" to "تم إيقاف الرقابة الأبوية",
        "Password" to "كلمة المرور",
        "Pick a channel to browse its past programmes." to "اختر قناة لتصفّح برامجها السابقة.",
        "Picture enhancement isn't supported on this device, playing the original picture" to "تحسين الصورة غير مدعوم على هذا الجهاز، سيتم عرض الصورة الأصلية",
        "Picture mode" to "وضع الصورة",
        "Picture-in-picture on Home button" to "صورة داخل صورة عند زر الرئيسية",
        "Play" to "تشغيل",
        "Play full screen" to "تشغيل بملء الشاشة",
        "Playback speed" to "سرعة التشغيل",
        "Player" to "المشغّل",
        "Player identity (User-Agent)" to "هوية المشغّل (User-Agent)",
        "Playlist" to "قائمة تشغيل",
        "Playlist URL (.m3u / .m3u8)" to "رابط القائمة (.m3u / .m3u8)",
        "Preparing…" to "جارٍ التحضير…",
        "Press OK to choose a channel" to "اضغط OK لاختيار قناة",
        "Press ▼ to browse the results" to "اضغط ▼ لتصفّح النتائج",
        "Profile name (anything you like)" to "اسم الملف (أي اسم تريده)",
        "Profile saved" to "تم حفظ الملف",
        "Programme reminders" to "تذكيرات البرامج",
        "Reading playlist…" to "جارٍ قراءة القائمة…",
        "Ready to watch" to "جاهز للمشاهدة",
        "Recent" to "الأخيرة",
        "Recently watched" to "شوهد مؤخرًا",
        "Recently watched channels" to "قنوات شوهدت مؤخرًا",
        "Record to" to "التسجيل إلى",
        "Recorded" to "مسجّل",
        "Recording" to "جارٍ التسجيل",
        "Recording location" to "مكان حفظ التسجيلات",
        "Recording started" to "بدأ التسجيل",
        "Recordings" to "التسجيلات",
        "Refresh channel list automatically" to "تحديث القنوات تلقائيًا",
        "Refresh content" to "تحديث المحتوى",
        "Refresh interval" to "فترة التحديث",
        "Reminder cancelled" to "تم إلغاء التذكير",
        "Remove from favourites" to "إزالة من المفضلة",
        "Removed from favourites" to "أُزيلت من المفضلة",
        "Repeat the new PIN" to "أعد إدخال الرقم السري",
        "Resume" to "استئناف",
        "Resume positions cleared" to "تم مسح مواضع الاستئناف",
        "Resume where I left off" to "المتابعة من حيث توقفت",
        "Rewind needs a channel with catch-up (⟲)" to "الإرجاع يحتاج قناة تدعم إعادة البث (⟲)",
        "Save profile" to "حفظ الملف",
        "Saving…" to "جارٍ الحفظ…",
        "Say a channel, movie or series" to "قل اسم قناة أو فيلم أو مسلسل",
        "Scheduled" to "مجدول",
        "Search" to "بحث",
        "Search channels" to "ابحث في القنوات",
        "Searching subtitles…" to "جارٍ البحث عن ترجمة…",
        "Select a channel to preview.\nPress OK again for full screen." to "اختر قناة للمعاينة.\nاضغط OK مرة أخرى لملء الشاشة.",
        "Select a past programme marked ⟲ to watch it again" to "اختر برنامجًا سابقًا عليه ⟲ لمشاهدته مجددًا",
        "Series" to "المسلسلات",
        "Server URL, e.g. http://example.com:8080" to "رابط الخادم، مثل http://example.com:8080",
        "Server URL, username and password are all required." to "رابط الخادم واسم المستخدم وكلمة المرور كلها مطلوبة.",
        "Set a parental PIN in Settings to lock categories" to "اضبط رقمًا سريًا في الإعدادات لقفل الفئات",
        "Set ✓" to "مضبوط ✓",
        "Settings" to "الإعدادات",
        "Shadow" to "ظل",
        "Signing in…" to "جارٍ تسجيل الدخول…",
        "Size, colour and background of subtitles" to "حجم الترجمة ولونها وخلفيتها",
        "Sleep timer" to "مؤقّت النوم",
        "Sleep timer off" to "تم إيقاف مؤقّت النوم",
        "Sleep timer: playback stopped" to "مؤقّت النوم: تم إيقاف التشغيل",
        "Small" to "صغير",
        "Stereo" to "ستيريو",
        "Stopped" to "متوقف",
        "Stretch" to "تمديد",
        "Subtitle background" to "خلفية الترجمة",
        "Subtitle colour" to "لون الترجمة",
        "Subtitle languages" to "لغات الترجمة",
        "Subtitle languages (online)" to "لغات الترجمة (من الإنترنت)",
        "Subtitle size" to "حجم الترجمة",
        "Subtitle style" to "شكل الترجمة",
        "Subtitles" to "الترجمة",
        "Subtitles added" to "تمت إضافة الترجمة",
        "Switch profile" to "تبديل الملف",
        "TMDB API key" to "مفتاح TMDB API",
        "TV guide" to "دليل البرامج",
        "TV guide & catch-up" to "دليل البرامج وإعادة البث",
        "TV guide time shift" to "فرق توقيت دليل البرامج",
        "TV storage" to "ذاكرة التلفاز",
        "The playlist is empty or not a valid M3U file" to "القائمة فارغة أو ليست ملف M3U صالحًا",
        "The server sent a message instead of video" to "أرسل الخادم رسالة بدلًا من الفيديو",
        "The server sent an empty response (stream offline or connection limit reached)." to "أرسل الخادم ردًا فارغًا (البث متوقف أو تم بلوغ حد الاتصالات).",
        "This channel has no catch-up archive" to "لا يوجد أرشيف إعادة بث لهذه القناة",
        "This channel has no catch-up archive, back to live" to "لا يوجد أرشيف لهذه القناة، العودة إلى البث المباشر",
        "This profile has no movies" to "لا توجد أفلام في هذا الملف",
        "This profile has no series" to "لا توجد مسلسلات في هذا الملف",
        "This server did not answer like an Xtream Codes panel" to "هذا الخادم لم يستجب كلوحة Xtream Codes",
        "This stream has no subtitles" to "لا توجد ترجمة لهذا البث",
        "Time format" to "تنسيق الوقت",
        "Trailer" to "الإعلان",
        "Turn off parental control" to "إيقاف الرقابة الأبوية",
        "Type, or press the mic and speak" to "اكتب، أو اضغط على الميكروفون وتحدّث",
        "Uncategorized" to "غير مصنّف",
        "Unlock this category" to "فتح قفل هذه الفئة",
        "Unnamed" to "بلا اسم",
        "Update available" to "يتوفر تحديث",
        "Username" to "اسم المستخدم",
        "Video quality" to "جودة الفيديو",
        "Voice search" to "البحث الصوتي",
        "Voice search isn't available on this TV" to "البحث الصوتي غير متاح على هذا التلفاز",
        "Watch now" to "شاهد الآن",
        "Welcome" to "أهلًا بك",
        "White" to "أبيض",
        "Wrong PIN" to "رقم سري خاطئ",
        "Wrong username or password" to "اسم المستخدم أو كلمة المرور غير صحيحة",
        "Xtream Codes login" to "تسجيل دخول Xtream Codes",
        "Yellow" to "أصفر",
        "Your provider offers no catch-up channels" to "مزوّدك لا يوفّر قنوات إعادة بث",
        "Your series" to "مسلسلاتك",
        "Zoom" to "تكبير",
        "Cannot play this stream." to "تعذّر تشغيل هذا البث.",
        "OK = open in VLC / MX Player" to "OK = فتح في VLC / MX Player",
        "▲▼ = other channel" to "▲▼ = قناة أخرى",
        "⏩  Back to live" to "⏩  العودة إلى المباشر",
        "⏪  Rewind 5 minutes" to "⏪  إرجاع 5 دقائق",
        "⏮  Watch this programme from the start" to "⏮  شاهد هذا البرنامج من البداية",
        "⏰  Cancel reminder" to "⏰  إلغاء التذكير",
        "⏰  Remind me when it starts" to "⏰  ذكّرني عند البدء",
        "⏰  Starting now" to "⏰  يبدأ الآن",
        "⏸  Pause live TV" to "⏸  إيقاف البث المباشر مؤقتًا",
        "⏸  Paused" to "⏸  متوقف مؤقتًا",
        "⏹  Stop recording" to "⏹  إيقاف التسجيل",
        "⏺  Record" to "⏺  تسجيل",
        "⏺  Record this programme" to "⏺  تسجيل هذا البرنامج",
        "▦  Multi-screen" to "▦  تعدّد الشاشات",
        "◀ ▶  one minute back / forward" to "◀ ▶  دقيقة للخلف / للأمام",
        "▲▼ or 0-9 change channel   ⏯ pause / rewind   ◀▶ menu   OK info" to "▲▼ أو 0-9 لتغيير القناة   ⏯ إيقاف/إرجاع   ◀▶ القائمة   OK معلومات",
        "▶  Play" to "▶  تشغيل",
        "▶  Resume" to "▶  استئناف",
        "▶  Trailer" to "▶  الإعلان",
        "▶  Watch live" to "▶  شاهد مباشرة",
        "★ Favourites" to "★ المفضلة",
        "☆ Favourite" to "☆ مفضلة",
        "✓  In my list" to "✓  في قائمتي",
        "✕  Cancel" to "✕  إلغاء",
        "⧉  Picture-in-picture" to "⧉  صورة داخل صورة",
        "🗑  Delete" to "🗑  حذف",
        "● NOW" to "● الآن",
        "⟲ catch-up" to "⟲ إعادة البث",
        "plug in a USB drive to record to it" to "صِل ذاكرة USB للتسجيل عليها",
        "⏯ continue from here" to "⏯ المتابعة من هنا",
        "⏯ resume" to "⏯ استئناف",
        "Original" to "الأصلي",
        "Enhanced" to "محسّن",
        "Vivid" to "زاهٍ",
        "Cinema" to "سينمائي",
        "Sharp" to "حاد",
        "Enhanced (sharper, richer colour)" to "محسّن (أوضح وألوان أغنى)",
        "Vivid (bright and punchy)" to "زاهٍ (ساطع وحيوي)",
        "Cinema (deep, natural)" to "سينمائي (عميق وطبيعي)",
        "Sharp (maximum detail)" to "حاد (أقصى قدر من التفاصيل)",
        "Language" to "اللغة",
        "Theme colour" to "لون الواجهة",
        "English" to "English",
        "العربية" to "العربية",
        "Violet" to "بنفسجي",
        "Ocean" to "محيطي",
        "Sunset" to "غروب",
        "Emerald" to "زمردي",
        "Gold" to "ذهبي",
        "Rose" to "وردي",
        "Yes" to "نعم",
        "No" to "لا",
        "OK" to "موافق",
        "Unlock" to "فتح",
        "Watch" to "مشاهدة",
        "Name" to "الاسم",
        "Remind me" to "ذكّرني",
        "Record" to "تسجيل",
        "Pause" to "إيقاف مؤقت",
        "Back to live" to "العودة إلى المباشر",
        "On" to "مفعّل",
        "Profiles" to "الملفات",
        "Guide" to "الدليل",
        "Favourites" to "المفضلة",
        "Episodes" to "الحلقات",
        "Seasons" to "المواسم",
        "Accent colour for buttons, highlights and progress bars" to "لون الأزرار والتحديد وأشرطة التقدّم",
        "Interface language (Arabic switches the layout to right-to-left)" to "لغة الواجهة (العربية تقلب الاتجاه من اليمين لليسار)",
        "Downloads" to "التنزيلات",
        "↻  Try again" to "↻  إعادة المحاولة",
        "Choose episodes to download" to "اختر الحلقات لتنزيلها",
        "🗑  Select & delete" to "🗑  تحديد وحذف",
        "🗑  Delete all" to "🗑  حذف الكل",
        "Select downloads to delete" to "اختر التنزيلات للحذف",
        "Delete all downloads?" to "حذف كل التنزيلات؟",
        "Removes every download from the list and deletes the files from the TV and any USB drive, including leftover files. This can't be undone." to "يزيل كل التنزيلات من القائمة ويحذف ملفاتها من التلفاز وأي وحدة USB، بما في ذلك الملفات المتبقية. لا يمكن التراجع عن ذلك.",
        "file missing" to "الملف غير موجود",
        "Audio decoding" to "فك ترميز الصوت",
        "Stereo sound" to "صوت ستيريو",
        "Video fit" to "ملاءمة الفيديو",
        "Watch later" to "للمشاهدة لاحقًا",
        "OK = play now   •   Back = close" to "OK = تشغيل الآن   •   رجوع = إغلاق",
        "⏭  Skip intro" to "⏭  تخطي المقدمة",
        "Intro skipped" to "تم تخطي المقدمة",
        "Play to where the opening titles end, then choose this again" to "شغّل حتى نهاية المقدمة ثم اختر هذا مرة أخرى",
        "⏭  Intro ends here (skip it in every episode)" to "⏭  المقدمة تنتهي هنا (تخطَّها في كل حلقة)",
        "✕  Clear skip intro for this series" to "✕  إلغاء تخطي المقدمة لهذا المسلسل",
        "Skip intro cleared" to "أُلغي تخطي المقدمة",
        "⟲  Replay the last 30 seconds" to "⟲  إعادة آخر 30 ثانية",
        "This channel can't go back" to "لا يمكن الرجوع في هذه القناة",
        "Back to live in the menu" to "العودة للبث المباشر من القائمة",
        "Skip intro" to "تخطي المقدمة",
        "Picture quality and data" to "جودة الصورة والبيانات",
        "Save data on mobile data / hotspot (recommended)" to "توفير البيانات على بيانات الجوال / نقطة الاتصال (موصى به)",
        "Always best quality" to "أفضل جودة دائمًا",
        "Always save data" to "توفير البيانات دائمًا",
        "Your provider refused. Your subscription may have expired, or another device is using your only connection: stop it and try again." to "رفض مزوّد الخدمة. قد يكون الاشتراك منتهيًا، أو جهاز آخر يستخدم اتصالك الوحيد: أوقفه وحاول مجددًا.",
        "This channel or video is offline at your provider right now. Try again later or pick another one." to "هذه القناة أو الفيديو غير متاح حاليًا لدى المزوّد. حاول لاحقًا أو اختر غيرها.",
        "Your provider's server has a problem. Try again in a few minutes." to "خادم المزوّد به مشكلة. حاول بعد دقائق.",
        "Usually the channel is offline, or your connection limit is reached." to "غالبًا القناة متوقفة، أو وصلت إلى حد الاتصالات.",
        "No connection to the server. Check the internet (Wi-Fi or hotspot) and try again." to "لا يوجد اتصال بالخادم. تحقق من الإنترنت (Wi-Fi أو نقطة الاتصال) وحاول مجددًا.",
        "This device can't decode this video. Try another quality, or open it in VLC." to "هذا الجهاز لا يستطيع تشغيل هذا الفيديو. جرّب جودة أخرى أو افتحه في VLC.",
        "The stream format isn't supported. Try Settings → Stream format, or open it in VLC." to "صيغة البث غير مدعومة. جرّب الإعدادات ← صيغة البث، أو افتحه في VLC.",
        "Try again, or choose another channel." to "حاول مجددًا أو اختر قناة أخرى.",
        "Downloaded" to "التنزيلات",
        "✕  Remove from Continue watching" to "✕  إزالة من متابعة المشاهدة",
        "✕  Remove from Watch later" to "✕  إزالة من المشاهدة لاحقًا",
        "🗑  Delete download" to "🗑  حذف التنزيل",
        "⬇  Open Downloads" to "⬇  فتح التنزيلات",
        "The file is missing (USB drive unplugged?)" to "الملف غير موجود (هل فُصلت وحدة USB؟)",
        "This channel isn't responding. Trying another stream type…" to "القناة لا تستجيب. جارٍ تجربة نوع بث آخر…",
        "🕒  Watch later" to "🕒  للمشاهدة لاحقًا",
        "✓  Watch later" to "✓  للمشاهدة لاحقًا",
        "Added to Watch later" to "أُضيف إلى قائمة المشاهدة لاحقًا",
        "Removed from Watch later" to "أُزيل من قائمة المشاهدة لاحقًا",
        "New this week: movies" to "جديد هذا الأسبوع: أفلام",
        "New this week: series" to "جديد هذا الأسبوع: مسلسلات",
        "Subtitle style" to "شكل الترجمة",
        "Subtitle size" to "حجم الترجمة",
        "Subtitle colour" to "لون الترجمة",
        "Subtitle background" to "خلفية الترجمة",
        "Start with the last channel" to "البدء بآخر قناة",
        "On: StreamBox opens straight into the channel you watched last" to "تشغيل: يفتح StreamBox مباشرة على آخر قناة شاهدتها",
        "Off: StreamBox opens on the home screen" to "إيقاف: يفتح StreamBox على الشاشة الرئيسية",
        "Number buttons" to "أزرار الأرقام",
        "Channel numbers" to "أرقام القنوات",
        "My favourites (1 = first favourite)" to "مفضلتي (1 = أول قناة مفضلة)",
        "Favourites: 1 = your first favourite channel, 2 = second…" to "المفضلة: 1 = أول قناة مفضلة، 2 = الثانية…",
        "Channel numbers from your provider" to "أرقام القنوات من مزوّد الخدمة",
        "Fast start" to "بدء سريع",
        "On: opens with the saved channel list, updates it in the background" to "تشغيل: يفتح بقائمة القنوات المحفوظة ويحدّثها في الخلفية",
        "Off: always waits for the latest list" to "إيقاف: ينتظر دائمًا أحدث قائمة",
        "Small window" to "نافذة صغيرة",
        "No favourite channels yet" to "لا توجد قنوات مفضلة بعد",
        "Add favourite channels in StreamBox" to "أضف قنوات مفضلة في StreamBox",
        "This is the last episode" to "هذه آخر حلقة",
        "This is the first episode" to "هذه أول حلقة",
        "Original" to "الأصلي",
        "Fill screen" to "ملء الشاشة",
        "Zoom 115%" to "تكبير 115%",
        "Remove black bars (cinema)" to "إزالة الأشرطة السوداء (سينما)",
        "Video fit: {}" to "ملاءمة الفيديو: {}",
        "Fit" to "ملاءمة",
        "Stretch" to "تمديد",
        "Zoom" to "تكبير",
        "On (cars, phones, TV speakers)" to "تشغيل (السيارة، الهاتف، سماعات التلفاز)",
        "Off (soundbar / receiver)" to "إيقاف (مكبر صوت خارجي / مستقبل)",
        "On: surround sound is mixed to 2 speakers (best for cars, phones, TV speakers)" to "تشغيل: يُدمج الصوت المحيطي في سماعتين (الأفضل للسيارة والهاتف وسماعات التلفاز)",
        "Off: surround sound is sent as is (for soundbars and receivers)" to "إيقاف: يُرسل الصوت المحيطي كما هو (لمكبرات الصوت والمستقبلات)",
        "Automatic (recommended)" to "تلقائي (موصى به)",
        "Always use the StreamBox decoder (no sound? try this)" to "استخدام مفكّك StreamBox دائمًا (لا يوجد صوت؟ جرّب هذا)",
        "StreamBox decoder (use this if there's no sound, e.g. in a car)" to "مفكّك StreamBox (استخدمه إذا لم يكن هناك صوت، مثل السيارة)",
        "Automatic (device first, StreamBox decoder for Dolby/DTS)" to "تلقائي (الجهاز أولًا، ومفكّك StreamBox لـ Dolby/DTS)",
        "Back" to "رجوع",
        "preparing fast jumps…" to "جارٍ تجهيز التقديم السريع…",
        "Nothing downloaded yet" to "لا توجد تنزيلات بعد",
        "Cancel" to "إلغاء",
        "Select all" to "تحديد الكل",
        "Select none" to "إلغاء التحديد",
        "Nothing new to download" to "لا يوجد جديد للتنزيل",
        "Download location" to "مكان التنزيلات",
        "Save new downloads to" to "حفظ التنزيلات الجديدة في",
        "TV storage" to "ذاكرة التلفاز",
        "New downloads go to the USB drive. Keep it plugged in to watch them" to "ستُحفظ التنزيلات الجديدة على وحدة USB. أبقها موصولة لمشاهدتها",
        "⬇  Download episodes" to "⬇  تنزيل الحلقات",
        "Your server sends this title as a live stream playlist, which can't be saved. Watch it online instead" to "الخادم يرسل هذا العنوان كقائمة بث مباشر لا يمكن حفظها. شاهده عبر الإنترنت بدلًا من ذلك",
        "The server sent an incomplete file. Choose Try again" to "أرسل الخادم ملفًا غير مكتمل. اختر إعادة المحاولة",
        "The server sent nothing" to "لم يرسل الخادم شيئًا",
        "Downloading" to "جارٍ التنزيل",
        "⬇  Download" to "⬇  تنزيل",
        "✓  Downloaded" to "✓  تم التنزيل",
        "⬇  In downloads" to "⬇  في التنزيلات",
        "⬇  Download this season" to "⬇  تنزيل هذا الموسم",
        "Added to downloads" to "أُضيف إلى التنزيلات",
        "Already in downloads" to "موجود في التنزيلات",
        "Waiting in the queue" to "في قائمة الانتظار",
        "Preparing…" to "جارٍ التحضير…",
        "Failed" to "فشل",
        "⏸  Pause" to "⏸  إيقاف مؤقت",
        "▶  Play" to "▶  تشغيل",
        "⬇  Resume download" to "⬇  متابعة التنزيل",
        "🗑  Delete" to "🗑  حذف",
        "Nothing downloaded yet. Open a movie or series and choose ⬇ Download." to "لا توجد تنزيلات بعد. افتح فيلمًا أو مسلسلًا واختر ⬇ تنزيل.",
        "Who's watching?" to "من يشاهد؟",
        "Everyone gets their own favourites and Continue watching." to "لكل شخص مفضلاته و«متابعة المشاهدة» الخاصة به.",
        "Hold OK on a profile to edit it" to "اضغط مطولًا على OK فوق الملف لتعديله",
        "Add profile" to "إضافة ملف",
        "Name" to "الاسم",
        "👤  Normal profile" to "👤  ملف عادي",
        "🧒  Kids profile" to "🧒  ملف أطفال",
        "✎  Rename" to "✎  إعادة تسمية",
        "🙂  Change picture" to "🙂  تغيير الصورة",
        "👤  Make a normal profile" to "👤  تحويل إلى ملف عادي",
        "🧒  Make a Kids profile" to "🧒  تحويل إلى ملف أطفال",
        "Choose a picture" to "اختر صورة",
        "Tip: set a parental PIN in Settings so kids can't leave their profile" to "نصيحة: عيّن رمز رقابة أبوية من الإعدادات حتى لا يخرج الأطفال من ملفهم",
    )

    private val templates: List<Pair<String, String>> = listOf(
        "Next episode in {} s" to "الحلقة التالية خلال {} ث",
        "Intro marked: {} will be skippable in every episode" to "تم تحديد المقدمة: يمكن تخطي {} في كل حلقة",
        "No favourite number {}" to "لا توجد قناة مفضلة رقم {}",
        "{} deleted" to "تم حذف {}",
        "{} episodes added to downloads" to "أُضيفت {} حلقات إلى التنزيلات",
        "Paused  •  {}" to "متوقف مؤقتًا  •  {}",
        "Ready to watch offline   •   {}" to "جاهز للمشاهدة دون إنترنت   •   {}",
        "Saved in {}   •   one download at a time" to "محفوظ في {}   •   تنزيل واحد في كل مرة",
        "   •   {} drives found" to "   •   تم العثور على {} وحدات تخزين",
        "Aspect ratio: {}" to "نسبة العرض: {}",
        "Background: {}" to "الخلفية: {}",
        "Build {} is ready (you have build {}).\nInstall it now? Your profiles and settings are kept." to "الإصدار {} جاهز (لديك الإصدار {}).\nهل تريد تثبيته الآن؟ ستبقى ملفاتك وإعداداتك.",
        "Channels ({})" to "القنوات ({})",
        "Movies ({})" to "الأفلام ({})",
        "Series ({})" to "المسلسلات ({})",
        "Colour: {}" to "اللون: {}",
        "Size: {}" to "الحجم: {}",
        "Connection lost. Reconnecting… ({}/5)" to "انقطع الاتصال. جارٍ إعادة الاتصال… ({}/5)",
        "Connections {}/{}" to "الاتصالات {}/{}",
        "Could not load episodes: {}" to "تعذّر تحميل الحلقات: {}",
        "Could not sign in: {}" to "تعذّر تسجيل الدخول: {}",
        "Couldn't open the installer: {}" to "تعذّر فتح المثبّت: {}",
        "Default ({})" to "افتراضي ({})",
        "Delete \"{}\"?" to "حذف «{}»؟",
        "Download failed: {}" to "فشل التنزيل: {}",
        "Episode {}" to "الحلقة {}",
        "Every {} hours" to "كل {} ساعات",
        "Expires {}" to "ينتهي {}",
        "Format: {}. The built-in player could not read it ({})." to "الصيغة: {}. لم يتمكن المشغّل المدمج من قراءتها ({}).",
        "I'll remind you at {}" to "سأذكّرك الساعة {}",
        "Installed: build {}. Checked automatically once a day" to "المثبّت: الإصدار {}. يتم التحقق تلقائيًا مرة يوميًا",
        "Loading failed: {}" to "فشل التحميل: {}",
        "M3U playlist  •  {}" to "قائمة M3U  •  {}",
        "Next  {}   {}" to "التالي  {}   {}",
        "Next: {}" to "التالي: {}",
        "No channel {}" to "لا توجد قناة رقم {}",
        "No channels in {}" to "لا توجد قنوات في {}",
        "Nothing found for “{}”" to "لا نتائج لـ «{}»",
        "Now  {} – {}" to "الآن  {} – {}",
        "Now: {}" to "الآن: {}",
        "Open the profile with {} first" to "افتح الملف الذي يحتوي على {} أولًا",
        "Picture mode: {}" to "وضع الصورة: {}",
        "Playback speed: {}x" to "سرعة التشغيل: {}x",
        "Playback stops in {} minutes" to "سيتوقف التشغيل بعد {} دقيقة",
        "Quality: {}" to "الجودة: {}",
        "Record \"{}\" until {}" to "تسجيل «{}» حتى {}",
        "Record the next {} minutes" to "تسجيل الـ {} دقيقة القادمة",
        "Recording scheduled: {}" to "تمت جدولة التسجيل: {}",
        "Recording to {}" to "التسجيل إلى {}",
        "Recording until {}" to "جارٍ التسجيل حتى {}",
        "Recording {} programmes" to "جارٍ تسجيل {} برامج",
        "Resume from {}" to "استئناف من {}",
        "Saved to: {}" to "محفوظ في: {}",
        "Season {}" to "الموسم {}",
        "Starting now: {}" to "يبدأ الآن: {}",
        "Subtitle search failed: {}" to "فشل البحث عن الترجمة: {}",
        "Track {}" to "المسار {}",
        "USB / SD card {}" to "USB / بطاقة SD {}",
        "What's new in build {}" to "الجديد في الإصدار {}",
        "Online  •  {} ms" to "متصل  •  {} مللي ثانية",
        "Reachable  •  {} ms" to "متاح  •  {} مللي ثانية",
        "Kids profiles can now see {}" to "يمكن لملفات الأطفال الآن رؤية {}",
        "Hidden channels ({})" to "القنوات المخفية ({})", "Hidden categories ({})" to "الفئات المخفية ({})",
        "Sort channels: {}" to "ترتيب القنوات: {}", "{} is visible again" to "{} ظاهرة مجددًا",
        "{}  (main)" to "{}  (الرئيسي)",
        "You have the latest version (build {})" to "لديك أحدث إصدار (الإصدار {})",
        "{} channels  •  {} movies  •  {} series" to "{} قناة  •  {} فيلم  •  {} مسلسل",
        "{} min left" to "متبقٍ {} دقيقة",
        "{} minutes" to "{} دقيقة",
        "{}\non {}" to "{}\nعلى {}",
        "on {}. Open StreamBox to watch" to "على {}. افتح StreamBox للمشاهدة",
        "↩  Previous channel: {}" to "↩  القناة السابقة: {}",
        "⏪  {} min behind live" to "⏪  متأخر {} دقيقة عن المباشر",
        "⏩ catch up" to "⏩ للتقدّم",
        "menu: Back to live" to "القائمة: العودة إلى المباشر",
        "⏺  Record until {}" to "⏺  تسجيل حتى {}",
        "⏺  Record {}" to "⏺  تسجيل {}",
        "● Live  •  {}" to "● مباشر  •  {}",
        "☾  Sleep timer: {}" to "☾  مؤقّت النوم: {}",
        "Reconnecting: {}" to "جارٍ إعادة الاتصال: {}",
        "The server answered HTTP {}" to "ردّ الخادم برمز HTTP {}",
        "This video is {}, which the built-in player does not support." to "هذا الفيديو بصيغة {}، وهي غير مدعومة في المشغّل المدمج.",
        "Scan with your phone camera.\nThe phone must be on the same Wi-Fi as the TV.\n\n{}" to "امسح بكاميرا هاتفك.\nيجب أن يكون الهاتف على نفس شبكة Wi-Fi.\n\n{}",
        "Xtream Codes  •  {}  •  {}" to "Xtream Codes  •  {}  •  {}",
        "Recording until {}" to "جارٍ التسجيل حتى {}",
        "Installed: build {}" to "المثبّت: الإصدار {}",
        "{} — {}" to "{} — {}",    )

    private val patterns: List<Pair<Regex, String>> by lazy {
        templates.map { (en, ar) ->
            val parts = en.split("{}")
            Regex("^" + parts.joinToString("(.+?)") { Regex.escape(it) } + "$", RegexOption.DOT_MATCHES_ALL) to ar
        }
    }

    private val separators = listOf("\n", "   •   ", "     ", "  •  ", "   ")
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun init(app: Application) {
        enabled = Prefs.language == "ar"
        AppCompatDelegate.setApplicationLocales(
            if (enabled) LocaleListCompat.forLanguageTags("ar") else LocaleListCompat.getEmptyLocaleList()
        )
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                a.theme.applyStyle(accentStyle(Prefs.accent), true)
            }
            override fun onActivityStarted(a: Activity) {
                if (a.window.decorView.layoutDirection == View.LAYOUT_DIRECTION_RTL) mirror(a.window.decorView)
            }
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** Changes the interface language; open screens are recreated by AppCompat. */
    fun setLanguage(code: String) {
        Prefs.language = code
        enabled = code == "ar"
        cache.clear()
        AppCompatDelegate.setApplicationLocales(
            if (enabled) LocaleListCompat.forLanguageTags("ar") else LocaleListCompat.getEmptyLocaleList()
        )
    }

    val accents = listOf("violet", "ocean", "sunset", "emerald", "gold", "rose")
    val accentNames = listOf("Violet", "Ocean", "Sunset", "Emerald", "Gold", "Rose")

    fun accentStyle(id: String): Int = when (id) {
        "ocean" -> R.style.Accent_Ocean
        "sunset" -> R.style.Accent_Sunset
        "emerald" -> R.style.Accent_Emerald
        "gold" -> R.style.Accent_Gold
        "rose" -> R.style.Accent_Rose
        else -> R.style.Accent_Violet
    }

    /** Left-to-right gradients (hero scrims) are flipped so text stays readable in Arabic. */
    private fun mirror(v: View) {
        if (v.tag == "mirror") v.scaleX = -1f
        if (v is ViewGroup) for (i in 0 until v.childCount) mirror(v.getChildAt(i))
    }

    fun t(text: CharSequence?): CharSequence? {
        if (!enabled || text == null || text.isEmpty() || text is Spanned) return text
        val s = text.toString()
        return cache.getOrPut(s) { translate(s, 0) }
    }

    fun s(text: String): String = t(text)?.toString() ?: text

    private fun translate(s: String, depth: Int): String {
        if (depth > 4) return s
        plain[s]?.let { return it }
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return s
        if (trimmed != s) {
            plain[trimmed]?.let { return s.replace(trimmed, it) }
        }
        for ((re, ar) in patterns) {
            val m = re.matchEntire(trimmed) ?: continue
            var out = ar
            for (g in m.groupValues.drop(1)) out = out.replaceFirst("{}", translate(g, depth + 1))
            return if (trimmed != s) s.replace(trimmed, out) else out
        }
        for (sep in separators) {
            if (!s.contains(sep)) continue
            val parts = s.split(sep)
            val tr = parts.map { if (it.isBlank()) it else translate(it, depth + 1) }
            if (tr != parts) return tr.joinToString(sep)
        }
        return s
    }
}

/** Installed through the theme's viewInflaterClass so every inflated text view translates itself. */
class TrViewInflater : AppCompatViewInflater() {
    override fun createTextView(context: Context, attrs: AttributeSet?): AppCompatTextView = TrTextView(context, attrs)
    override fun createButton(context: Context, attrs: AttributeSet?): AppCompatButton = TrButton(context, attrs)
    override fun createEditText(context: Context, attrs: AttributeSet?): AppCompatEditText = TrEditText(context, attrs)
    override fun createCheckedTextView(context: Context, attrs: AttributeSet?): AppCompatCheckedTextView = TrCheckedTextView(context, attrs)
}

class TrTextView(c: Context, a: AttributeSet?) : AppCompatTextView(c, a) {
    override fun setText(text: CharSequence?, type: BufferType?) = super.setText(Tr.t(text), type)
}

class TrButton(c: Context, a: AttributeSet?) : AppCompatButton(c, a) {
    override fun setText(text: CharSequence?, type: BufferType?) = super.setText(Tr.t(text), type)
}

class TrCheckedTextView(c: Context, a: AttributeSet?) : AppCompatCheckedTextView(c, a) {
    override fun setText(text: CharSequence?, type: BufferType?) = super.setText(Tr.t(text), type)
}

class TrEditText(c: Context, a: AttributeSet?) : AppCompatEditText(c, a) {
    init {
        hint?.let { hint = Tr.t(it) }
    }
}
