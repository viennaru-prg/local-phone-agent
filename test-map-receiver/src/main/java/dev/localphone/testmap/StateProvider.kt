package dev.localphone.testmap

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri

/** Fixture diagnostics/configuration. This exported provider exists ONLY in the testOnly APK. */
class StateProvider : ContentProvider() {
    private val prefs get() = context!!.getSharedPreferences("fixture", 0)
    override fun onCreate() = true
    override fun getType(uri: Uri) = "vnd.android.cursor.item/test-map-state"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
        MatrixCursor(arrayOf("mode", "screen", "saved_clicks", "searches", "navigation_starts", "generic_clicks", "last_query", "place", "uri",
            "home_work_clicks", "frequent_clicks", "my_clicks", "favorites_clicks", "registration_clicks", "back_actions", "gesture_taps", "ignored_accessibility_clicks")).apply {
            addRow(arrayOf<Any?>(prefs.getString("mode", "legacy"), prefs.getString("screen", ""), prefs.getInt("saved_clicks", 0),
                prefs.getInt("searches", 0), prefs.getInt("navigation_starts", 0), prefs.getInt("generic_clicks", 0),
                prefs.getString("last_query", ""), prefs.getString("place", ""), prefs.getString("uri", ""),
                prefs.getInt("home_work_clicks", 0), prefs.getInt("frequent_clicks", 0), prefs.getInt("my_clicks", 0),
                prefs.getInt("favorites_clicks", 0), prefs.getInt("registration_clicks", 0), prefs.getInt("back_actions", 0),
                prefs.getInt("gesture_taps", 0), prefs.getInt("ignored_accessibility_clicks", 0)))
        }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val mode = values?.getAsString("mode") ?: return 0
        require(mode in listOf("legacy", "saved_office", "saved_search", "public_search", "ambiguous_office", "public_office", "failed_navigation", "no_coordinates", "generic",
            "personal_priority", "personal_home", "frequent_office", "frequent_named", "frequent_duplicate", "frequent_scroll", "personal_missing", "my_favorites", "ai_notes",
            "delayed_navigation", "countdown_navigation", "auto_navigation", "wrong_auto_navigation", "acknowledged_navigation",
            "semantic_button_navigation", "hidden_destination_auto_navigation", "wrong_hidden_destination_auto_navigation"))
        prefs.edit().clear().putString("mode", mode).commit(); return 1
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
