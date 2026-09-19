package uk.noammm.kav.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.KavModel
import uk.noammm.kav.PendingBackup
import uk.noammm.kav.Prefs
import uk.noammm.kav.data.Backup

@Composable
fun SettingsScreen(model: KavModel, onClose: () -> Unit) {
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().background(K.bg).verticalScroll(rememberScrollState())
        .padding(bottom = LocalBottomBarInset.current)) {
        ScreenHeader(T("Your", "ההגדרות"), T("settings", "שלכם"), back = onClose)

        Group(T("language", "שפה"))
        LanguageRow(ctx)

        Group(T("colour", "צבע"))
        AccentPreview(Modifier.padding(horizontal = K.gap4))
        Spacer(Modifier.height(K.gap4))
        AccentPicker(wheel = 200.dp) { Prefs.setAccent(ctx, it.toArgb()) }

        Group(T("what a plan may show", "מה מסלול יכול לכלול"))
        FilterRows(model.filters) { f, on -> model.setFilter(ctx, f, on) }

        Group(T("what a card shows", "מה מוצג בכרטיס"))
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            SwitchRow(
                T("Emissions", "פליטות"),
                T(
                    "The CO2e figure on every plan and on the trip you open.",
                    "נתון ה-CO2e על כל מסלול ועל הנסיעה שאתם פותחים.",
                ),
                Shown.co2,
                { on -> Shown.co2 = on; Prefs.setShowCo2(ctx, on) },
            ) { GlobeGlyph(if (Shown.co2) K.text else K.dim, K.surface1, 18.dp) }
        }

        Group(T("your data", "הנתונים שלכם"))
        BackupSection(model)

        Group(T("updates", "עדכונים"))
        UpdateSection(model)

        Group(T("what is not in here", "מה לא נמצא כאן"))
        Absent(
            T("No account", "אין חשבון"),
            T(
                "There is no sign-in, no profile, no sync. Nothing identifies you to anyone.",
                "אין התחברות, אין פרופיל, אין סנכרון. שום דבר כאן לא מזהה אתכם בפני איש.",
            ),
        )
        Absent(
            T("No adverts", "אין פרסומות"),
            T(
                "The official app carries Vungle video ads (1,038 class references), " +
                    "AdMob and Facebook Audience Network. Kav calls none of the ad endpoints, and never " +
                    "requests ad targeting.",
                "האפליקציה הרשמית כוללת פרסומות וידאו של Vungle (1,038 הפניות למחלקות), " +
                    "AdMob ו־Facebook Audience Network. Kav לא פונה לאף אחת מנקודות הקצה הפרסומיות, " +
                    "ולעולם לא מבקשת מיקוד פרסומי.",
            ),
        )
        Absent(
            T("No analytics or attribution", "אין אנליטיקה או ייחוס"),
            T(
                "Braze (~1,100), AppsFlyer (~890), Adjust, " +
                    "Firebase Crashlytics (443) and Facebook SDK (~700) are all absent.",
                "Braze (כ־1,100), AppsFlyer (כ־890), Adjust, " +
                    "Firebase Crashlytics (443) ו־Facebook SDK (כ־700): כולם לא נמצאים כאן.",
            ),
        )
        Absent(
            T("No support chat", "אין צ'אט תמיכה"),
            T("Zendesk (~1,800 references) is not here either.", "גם Zendesk (כ־1,800 הפניות) לא נמצאת כאן."),
        )
        Absent(
            T("No upsell", "אין מכירה נוספת"),
            T(
                "There is no premium tier to be offered, so nothing in this app " +
                    "has a reason to interrupt you.",
                "אין גרסת פרימיום להציע, ולכן לשום דבר באפליקציה הזו אין סיבה להפריע לכם.",
            ),
        )
        Spacer(Modifier.height(K.gap8))
    }
}

@Composable
private fun LanguageRow(ctx: android.content.Context) {
    Row(
        Modifier.padding(horizontal = K.gap4).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        for (l in Lang.entries) {
            Chip(l.label, T.lang == l) { T.switchTo(l); Prefs.setLang(ctx, l) }
        }
    }
}

@Composable
private fun BackupSection(model: KavModel) {
    val ctx = LocalContext.current
    fun toast(s: String) = android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_SHORT).show()

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        Backup.write(ctx, uri)
            .onSuccess { toast(T("Backup saved", "הגיבוי נשמר")) }
            .onFailure { toast(T("Couldn't write that file", "לא ניתן היה לכתוב את הקובץ")) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) PendingBackup.uri = uri
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = K.gap3),
        verticalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            ActionTile(
                Modifier.weight(1f),
                T("Import", "ייבוא"),
                T("from a .kav file", "מקובץ ‎.kav"),
            ) { open.launch(arrayOf("*/*")) }
            ActionTile(
                Modifier.weight(1f),
                T("Export", "ייצוא"),
                T("to a .kav file", "לקובץ ‎.kav"),
            ) { save.launch(Backup.suggestedName()) }
        }
        Text(
            T(
                "A .kav file holds your saved places, trip history, searches and settings. " +
                    "Importing replaces what is here; the map is not part of it.",
                "קובץ ‎.kav מכיל את המקומות השמורים, היסטוריית הנסיעות, החיפושים וההגדרות שלכם. " +
                    "ייבוא מחליף את מה שנמצא כאן; המפה אינה חלק ממנו.",
            ),
            fontSize = 11.sp, color = K.dim, lineHeight = 16.sp,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp),
        )
    }
}

internal fun importError(e: Throwable): String =
    if (e is Backup.NotABackup) T("That file isn't a Kav backup.", "הקובץ הזה אינו גיבוי של Kav.")
    else T("Couldn't read that file.", "לא ניתן היה לקרוא את הקובץ.")

@Composable
private fun ActionTile(modifier: Modifier, label: String, sub: String, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(K.plate)
            .border(1.dp, K.border, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap3, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium)
        Text(sub, fontSize = 11.sp, color = K.dim, modifier = Modifier.padding(top = 2.dp))
    }
}

internal fun openLink(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun Group(title: String) {
    Text(
        title, style = DisplayItalic, fontSize = 12.sp, color = K.dim,
        modifier = Modifier.padding(start = K.gap4, end = K.gap4, top = K.gap5, bottom = K.gap2),
    )
}

@Composable
private fun Absent(title: String, desc: String) {
    Row(
        Modifier.padding(horizontal = K.gap4, vertical = K.gap2).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Box(
            Modifier.padding(top = 6.dp).width(14.dp).height(1.dp).background(K.borderStrong),
        )
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = K.text)
            Text(desc, fontSize = 11.sp, color = K.dim, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}
