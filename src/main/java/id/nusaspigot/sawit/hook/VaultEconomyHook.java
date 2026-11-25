package id.nusaspigot.sawit.hook;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Soft-depend bridge for Vault Economy providers (EssentialsX, CMI, etc.).
 */
public final class VaultEconomyHook {

    private Economy economy;
    private final DecimalFormat numberFormat;

    public VaultEconomyHook() {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.forLanguageTag("id-ID"));
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        this.numberFormat = new DecimalFormat("#,##0.##", symbols);
    }

    public boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            this.economy = null;
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            this.economy = null;
            return false;
        }
        this.economy = rsp.getProvider();
        return this.economy != null;
    }

    public boolean isAvailable() {
        if (this.economy == null) {
            setup();
        }
        return this.economy != null;
    }

    public double balance(OfflinePlayer player) {
        if (!isAvailable()) {
            return 0.0D;
        }
        return economy.getBalance(player);
    }

    public boolean has(OfflinePlayer player, double amount) {
        if (amount <= 0.0D) {
            return true;
        }
        return isAvailable() && economy.has(player, amount);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (amount <= 0.0D) {
            return true;
        }
        if (!isAvailable()) {
            return false;
        }
        EconomyResponse res = economy.withdrawPlayer(player, amount);
        return res != null && res.transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (amount <= 0.0D) {
            return true;
        }
        if (!isAvailable()) {
            return false;
        }
        EconomyResponse res = economy.depositPlayer(player, amount);
        return res != null && res.transactionSuccess();
    }

    public String format(String symbol, double amount) {
        return symbol + " " + numberFormat.format(amount);
    }
}
