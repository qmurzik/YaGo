package com.qmurzik.yago;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class SettingsActivity extends AppCompatActivity {
    private static final String TARGET = "ru.yandex.taxi";
    private static final String[] TYPES = {"text", "desc", "id", "class"};
    private static final int[] TYPE_BUTTONS = {R.id.type_text, R.id.type_desc, R.id.type_id, R.id.type_class};

    private SharedPreferences prefs;
    private boolean worldReadable;
    private final List<RuleEntry> rules = new ArrayList<>();
    private final List<View> dependents = new ArrayList<>();

    private View root;
    private LinearLayout rulesList;
    private TextView rulesEmpty;
    private ChipGroup hostsGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = openPrefs();
        setContentView(R.layout.activity_settings);
        setSupportActionBar(findViewById(R.id.toolbar));
        root = findViewById(R.id.root);

        // --- общий выключатель и статус
        MaterialSwitch master = findViewById(R.id.master);
        master.setChecked(prefs.getBoolean(Config.K_ENABLED, Config.D_ENABLED));
        master.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(Config.K_ENABLED, on).apply();
            updateStatus();
        });

        // --- скрытие элементов
        LinearLayout picker = findViewById(R.id.group_picker);
        addSwitch(picker, R.string.pref_inspector, R.string.pref_inspector_sum, Config.K_INSPECTOR, Config.D_INSPECTOR);
        addSwitch(picker, R.string.pref_fab, R.string.pref_fab_sum, Config.K_FAB, Config.D_FAB);

        // --- свои правила
        rulesList = findViewById(R.id.rules_list);
        rulesEmpty = findViewById(R.id.rules_empty);
        rules.addAll(RuleEntry.parseList(prefs.getString(Config.K_RULES, "")));
        renderRules();
        findViewById(R.id.rules_add).setOnClickListener(v -> editRule(-1));
        findViewById(R.id.rules_import).setOnClickListener(v -> importRules());
        findViewById(R.id.rules_export).setOnClickListener(v -> exportRules());

        // --- реклама
        LinearLayout ads = findViewById(R.id.group_ads);
        addSwitch(ads, R.string.pref_block_hosts, R.string.pref_block_hosts_sum, Config.K_BLOCK_AD_HOSTS, Config.D_BLOCK_AD_HOSTS);
        addSwitch(ads, R.string.pref_block_analytics, R.string.pref_block_analytics_sum, Config.K_BLOCK_ANALYTICS, Config.D_BLOCK_ANALYTICS);
        addSwitch(ads, R.string.pref_hide_ads, R.string.pref_hide_ads_sum, Config.K_HIDE_AD_VIEWS, Config.D_HIDE_AD_VIEWS);
        addSwitch(ads, R.string.pref_hide_labeled, R.string.pref_hide_labeled_sum, Config.K_HIDE_AD_LABELED, Config.D_HIDE_AD_LABELED);

        // --- домены
        ((TextView) findViewById(R.id.hosts_caption)).setText(getString(R.string.hosts_caption,
                Config.AD_HOSTS.length + Config.ANALYTICS_HOSTS.length));
        hostsGroup = findViewById(R.id.hosts_group);
        renderHosts();
        findViewById(R.id.hosts_add).setOnClickListener(v -> addHost());

        // --- прочее
        LinearLayout misc = findViewById(R.id.group_misc);
        addSwitch(misc, R.string.pref_screenshots, R.string.pref_screenshots_sum, Config.K_ALLOW_SCREENSHOTS, Config.D_ALLOW_SCREENSHOTS);
        addSwitch(misc, R.string.pref_debug, R.string.pref_debug_sum, Config.K_DEBUG_LOG, Config.D_DEBUG_LOG);

        findViewById(R.id.btn_open).setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage(TARGET);
            if (i != null) startActivity(i);
            else Snackbar.make(root, R.string.not_installed, Snackbar.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_stop).setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", TARGET, null))));
        ((TextView) findViewById(R.id.version)).setText(getString(R.string.version, BuildConfig.VERSION_NAME));

        updateStatus();
    }

    @SuppressLint("WorldReadableFiles")
    @SuppressWarnings("deprecation")
    private SharedPreferences openPrefs() {
        try {
            // LSPosed (xposedsharedprefs) разрешает MODE_WORLD_READABLE, только если модуль активен.
            SharedPreferences p = getSharedPreferences(Config.PREFS, Context.MODE_WORLD_READABLE);
            worldReadable = true;
            return p;
        } catch (SecurityException e) {
            worldReadable = false;
            return getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
        }
    }

    private void updateStatus() {
        boolean active = ModuleStatus.isActive() || worldReadable;
        boolean on = prefs.getBoolean(Config.K_ENABLED, Config.D_ENABLED);
        MaterialCardView card = findViewById(R.id.status_card);
        ImageView icon = findViewById(R.id.status_icon);
        TextView title = findViewById(R.id.status_title);
        TextView text = findViewById(R.id.status_text);

        int bg, fg;
        if (!active) {
            bg = MaterialColors.getColor(card, com.google.android.material.R.attr.colorErrorContainer);
            fg = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnErrorContainer);
            icon.setImageResource(R.drawable.ic_warning);
            title.setText(R.string.status_inactive);
            text.setText(R.string.status_inactive_text);
        } else if (!on) {
            bg = MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurfaceContainerHighest);
            fg = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnSurface);
            icon.setImageResource(R.drawable.ic_stop);
            title.setText(R.string.status_off);
            text.setText(R.string.status_off_text);
        } else {
            bg = MaterialColors.getColor(card, com.google.android.material.R.attr.colorPrimaryContainer);
            fg = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnPrimaryContainer);
            icon.setImageResource(R.drawable.ic_check);
            title.setText(R.string.status_active);
            text.setText(R.string.status_active_text);
        }
        card.setCardBackgroundColor(bg);
        icon.setImageTintList(ColorStateList.valueOf(fg));
        title.setTextColor(fg);
        text.setTextColor(fg);
        for (View v : dependents) v.setAlpha(on ? 1f : 0.5f);
    }

    // ================================================================ переключатели

    private void addSwitch(LinearLayout group, int title, int summary, String key, boolean def) {
        if (group.getChildCount() > 0) group.addView(divider());
        View row = LayoutInflater.from(this).inflate(R.layout.item_switch, group, false);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.summary)).setText(summary);
        MaterialSwitch sw = row.findViewById(R.id.toggle);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(key, on).apply());
        row.setOnClickListener(v -> sw.toggle());
        group.addView(row);
        dependents.add(row);
    }

    private View divider() {
        MaterialDivider d = new MaterialDivider(this);
        int inset = dp(16);
        d.setDividerInsetStart(inset);
        d.setDividerInsetEnd(inset);
        return d;
    }

    // ================================================================ правила

    private void saveRules() {
        prefs.edit().putString(Config.K_RULES, RuleEntry.toJson(rules)).apply();
    }

    private void renderRules() {
        rulesList.removeAllViews();
        rulesEmpty.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < rules.size(); i++) {
            final int index = i;
            final RuleEntry e = rules.get(i);
            if (i > 0) rulesList.addView(divider());
            View row = LayoutInflater.from(this).inflate(R.layout.item_rule, rulesList, false);
            ((TextView) row.findViewById(R.id.title)).setText(e.title());
            TextView ruleText = row.findViewById(R.id.rule);
            ruleText.setText(e.rule);
            ruleText.setVisibility(e.label.isEmpty() ? View.GONE : View.VISIBLE);
            MaterialSwitch sw = row.findViewById(R.id.toggle);
            sw.setChecked(e.enabled);
            sw.setOnCheckedChangeListener((b, on) -> {
                e.enabled = on;
                saveRules();
            });
            row.findViewById(R.id.delete).setOnClickListener(v -> {
                rules.remove(index);
                saveRules();
                renderRules();
                Snackbar.make(root, R.string.deleted, Snackbar.LENGTH_LONG)
                        .setAction(R.string.undo, u -> {
                            rules.add(Math.min(index, rules.size()), e);
                            saveRules();
                            renderRules();
                        }).show();
            });
            row.setOnClickListener(v -> editRule(index));
            rulesList.addView(row);
        }
    }

    private void editRule(final int index) {
        final RuleEntry existing = index >= 0 ? rules.get(index) : null;
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_rule, null);
        final EditText label = v.findViewById(R.id.label);
        final MaterialButtonToggleGroup type = v.findViewById(R.id.type);
        final EditText value = v.findViewById(R.id.value);
        final TextInputLayout valueLayout = v.findViewById(R.id.value_layout);
        final MaterialSwitch regex = v.findViewById(R.id.regex);
        final EditText up = v.findViewById(R.id.up);
        final TextView preview = v.findViewById(R.id.preview);

        if (existing != null) {
            label.setText(existing.label);
            String[] parts = RuleEntry.split(existing.rule);
            if (parts != null) {
                for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equals(parts[0])) type.check(TYPE_BUTTONS[i]);
                value.setText(parts[1]);
                up.setText(parts[2]);
                regex.setChecked(true);
            } else {
                value.setText(existing.rule);
                regex.setChecked(true);
            }
        } else {
            up.setText("0");
        }

        // путь вниз (>i>j) и keep из пикера в редакторе не показываем, но сохраняем
        String[] partsForExtra = existing != null ? RuleEntry.split(existing.rule) : null;
        final String keptPath = partsForExtra != null ? partsForExtra[3] : "";
        final boolean keptKeep = partsForExtra != null && !partsForExtra[4].isEmpty();
        final String[] result = new String[1];
        final Runnable refresh = () -> {
            String t = TYPES[0];
            for (int i = 0; i < TYPE_BUTTONS.length; i++) if (type.getCheckedButtonId() == TYPE_BUTTONS[i]) t = TYPES[i];
            String raw = value.getText() == null ? "" : value.getText().toString().trim();
            int level = 0;
            try {
                level = Integer.parseInt(up.getText() == null ? "0" : up.getText().toString());
            } catch (NumberFormatException ignored) {
            }
            valueLayout.setHelperText(regex.isChecked() ? null : literalHelp(t));
            if (raw.isEmpty()) {
                result[0] = null;
                preview.setText("—");
                valueLayout.setError(null);
                return;
            }
            String pattern = regex.isChecked() ? raw : literal(t, raw);
            try {
                Pattern.compile(pattern);
                valueLayout.setError(null);
                result[0] = RuleEntry.compose(t, pattern, level, keptPath, keptKeep);
                preview.setText(result[0]);
            } catch (PatternSyntaxException ex) {
                valueLayout.setError(getString(R.string.rule_invalid));
                result[0] = null;
                preview.setText("—");
            }
        };
        TextWatcher watcher = new SimpleWatcher(refresh);
        value.addTextChangedListener(watcher);
        up.addTextChangedListener(watcher);
        type.addOnButtonCheckedListener((g, id, checked) -> refresh.run());
        regex.setOnCheckedChangeListener((b, on) -> refresh.run());
        refresh.run();

        new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? R.string.rule_new : R.string.rule_edit)
                .setView(v)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    if (result[0] == null) return;
                    String l = label.getText() == null ? "" : label.getText().toString().trim();
                    if (existing != null) {
                        existing.label = l;
                        existing.rule = result[0];
                    } else {
                        rules.add(new RuleEntry(l, result[0], true));
                    }
                    saveRules();
                    renderRules();
                })
                .show();
    }

    private String literal(String type, String s) {
        String e = RuleEntry.escape(s);
        return "class".equals(type) ? e + "$" : "^\\s*" + e + "\\s*$";
    }

    private String literalHelp(String type) {
        switch (type) {
            case "id":
                return "Имя ресурса целиком, например promo_banner";
            case "class":
                return "Окончание имени класса, например StoriesView";
            case "desc":
                return "Описание для TalkBack целиком";
            default:
                return "Текст элемента целиком, как на экране";
        }
    }

    private void importRules() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        CharSequence text = null;
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        }
        int added = 0;
        if (!TextUtils.isEmpty(text)) {
            Set<String> have = new LinkedHashSet<>();
            for (RuleEntry r : rules) have.add(r.rule);
            for (RuleEntry r : RuleEntry.parseList(text.toString())) {
                if (RuleEntry.split(r.rule) != null && have.add(r.rule)) {
                    rules.add(r);
                    added++;
                }
            }
        }
        if (added > 0) {
            saveRules();
            renderRules();
            Snackbar.make(root, getString(R.string.rules_imported, added), Snackbar.LENGTH_SHORT).show();
        } else {
            Snackbar.make(root, R.string.rules_import_none, Snackbar.LENGTH_SHORT).show();
        }
    }

    private void exportRules() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("YaGo rules", RuleEntry.toLines(rules)));
        Snackbar.make(root, R.string.rules_exported, Snackbar.LENGTH_SHORT).show();
    }

    // ================================================================ домены

    private List<String> hosts() {
        List<String> out = new ArrayList<>();
        for (String h : prefs.getString(Config.K_EXTRA_HOSTS, "").split("[\\s,;]+")) {
            h = h.trim().toLowerCase(Locale.ROOT);
            if (!h.isEmpty() && !out.contains(h)) out.add(h);
        }
        return out;
    }

    private void saveHosts(List<String> list) {
        prefs.edit().putString(Config.K_EXTRA_HOSTS, TextUtils.join("\n", list)).apply();
    }

    private void renderHosts() {
        hostsGroup.removeAllViews();
        for (final String h : hosts()) {
            Chip c = new Chip(this);
            c.setText(h);
            c.setCloseIconVisible(true);
            c.setOnCloseIconClickListener(v -> {
                List<String> list = hosts();
                list.remove(h);
                saveHosts(list);
                renderHosts();
            });
            hostsGroup.addView(c);
        }
        hostsGroup.setVisibility(hostsGroup.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void addHost() {
        TextInputLayout layout = new TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        layout.setHint(getString(R.string.hosts_hint));
        EditText input = new com.google.android.material.textfield.TextInputEditText(layout.getContext());
        input.setSingleLine(true);
        layout.addView(input);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(layout, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hosts_add)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String h = input.getText() == null ? "" : input.getText().toString();
                    h = h.trim().toLowerCase(Locale.ROOT).replaceFirst("^[a-z]+://", "").replaceFirst("[/:].*$", "");
                    if (h.isEmpty()) return;
                    List<String> list = hosts();
                    if (!list.contains(h)) list.add(h);
                    saveHosts(list);
                    renderHosts();
                })
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final class SimpleWatcher implements TextWatcher {
        private final Runnable r;

        SimpleWatcher(Runnable r) {
            this.r = r;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s) {
            r.run();
        }
    }
}
