package harness;
import com.nisovin.shopkeepers.api.ShopkeepersAPI;
import com.nisovin.shopkeepers.api.shopkeeper.*;
import com.nisovin.shopkeepers.api.shopkeeper.admin.*;
import com.nisovin.shopkeepers.api.shopkeeper.admin.regular.*;
import com.nisovin.shopkeepers.api.shopkeeper.offers.*;
import com.nisovin.shopkeepers.api.shopobjects.*;
import com.nisovin.shopkeepers.api.events.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
public final class Probe extends JavaPlugin implements Listener {
 private final Map<UUID,Shopkeeper> shops=new java.util.concurrent.ConcurrentHashMap<>();
 private int clicks=0,trades=0;
 public void onEnable(){getServer().getPluginManager().registerEvents(this,this);getLogger().info("PLAYER_PROBE ENABLED");}
 @EventHandler public void join(PlayerJoinEvent e){e.getPlayer().getScheduler().runDelayed(this,t->{e.getPlayer().setOp(true);e.getPlayer().setGameMode(GameMode.CREATIVE);getLogger().info("PLAYER_PROBE JOIN "+e.getPlayer().getName());},null,20);}
 @EventHandler(priority=EventPriority.MONITOR) public void click(InventoryClickEvent e){clicks++;getLogger().info("PLAYER_PROBE CLICK type="+e.getView().getType()+" slot="+e.getRawSlot()+" action="+e.getAction()+" cancelled="+e.isCancelled());}
 @EventHandler(priority=EventPriority.MONITOR) public void trade(ShopkeeperTradeEvent e){trades++;getLogger().info("PLAYER_PROBE TRADE_EVENT cancelled="+e.isCancelled());}
 public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){if(!(sender instanceof Player p)){if(args.length>0&&args[0].equals("count")) Bukkit.getGlobalRegionScheduler().run(this,t->Lifecycle.log(this,"COUNT value="+ShopkeepersAPI.getShopkeeperRegistry().getAllShopkeepers().size()+" global="+Bukkit.isGlobalTickThread()));return true;}try{
 String op=args.length==0?"status":args[0];
 if(op.equals("setup")){
  p.closeInventory();p.getInventory().clear();p.getInventory().setItem(0,new ItemStack(Material.EMERALD,8));
  var loc=p.getLocation().clone().add(2,0,0);var typ=DefaultShopObjectTypes.LIVING().get(EntityType.VILLAGER);
  var s=(RegularAdminShopkeeper)ShopkeepersAPI.getShopkeeperRegistry().createShopkeeper(AdminShopCreationData.create(null,DefaultShopTypes.ADMIN_REGULAR(),typ,loc,null));
  s.setName("PlayerTradeProbe");s.addOffer(TradeOffer.create(new ItemStack(Material.DIAMOND,1),new ItemStack(Material.EMERALD,2),null));shops.put(p.getUniqueId(),s);s.save();ShopkeepersAPI.getShopkeeperStorage().save();say(p,"SETUP PASS uuid="+s.getUniqueId());
 }else if(op.equals("reload")){Lifecycle.reload(this,p,shops);}
 else if(op.equals("move")||op.equals("badmove")){Lifecycle.move(this,p,shops.get(p.getUniqueId()),op.equals("badmove"));}
 else if(op.equals("local")){Lifecycle.local(this,p);}
 else if(op.equals("editor")){say(p,"EDITOR opened="+shops.get(p.getUniqueId()).openEditorWindow(p));}
 else if(op.equals("trade")){p.closeInventory();p.setGameMode(GameMode.SURVIVAL);say(p,"TRADING opened="+shops.get(p.getUniqueId()).openTradingWindow(p));}
 else if(op.equals("close")){p.closeInventory();say(p,"CLOSE PASS");}
 else {int emerald=0,diamond=0;for(ItemStack i:p.getInventory().getContents())if(i!=null){if(i.getType()==Material.EMERALD)emerald+=i.getAmount();if(i.getType()==Material.DIAMOND)diamond+=i.getAmount();}say(p,"BALANCE emerald="+emerald+" diamond="+diamond+" clicks="+clicks+" trades="+trades);if(emerald==6&&diamond==1&&trades==1)say(p,"TRADE_CONSERVATION PASS");}
 }catch(Throwable e){getLogger().log(java.util.logging.Level.SEVERE,"PLAYER_PROBE FAIL",e);}return true;}
 private void say(Player p,String s){getLogger().info("PLAYER_PROBE "+s);p.sendMessage("PLAYER_PROBE "+s);}
}
