package org.macver.blip;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potion;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import org.jetbrains.annotations.NotNull;
import org.macver.blip.mixin.client.PlayerInventoryAccessor;

import java.util.*;
import java.util.stream.Stream;

public class ItemSearcher {
    private static final FuzzyScore fuzzyScore = new FuzzyScore(Locale.ENGLISH);

    public List<ItemStack> searchItems(String query) {
        Stream<ItemStack> stacks;
        // only show all items if in creative
        if (MinecraftClient.getInstance().player.getAbilities().creativeMode) {
            stacks = Registries.ITEM.stream()
                    .filter(item -> item != Items.ENCHANTED_BOOK || item != Items.AIR)
                    .map(Item::getDefaultStack);
            ArrayList<ItemStack> additionalStacks = new ArrayList<>(Collections.emptyList());
            DynamicRegistryManager registryManager = MinecraftClient.getInstance().player.getWorld().getRegistryManager();
            Registry<Enchantment> enchantments = registryManager.getOrThrow(RegistryKeys.ENCHANTMENT);
            for (Enchantment enchantment : enchantments) {
                for (int i = enchantment.getMinLevel(); i <= enchantment.getMaxLevel(); i++) {
                    ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
                    stack.addEnchantment(enchantments.getEntry(enchantment), i);
                    additionalStacks.add(stack);
                }
            }
            for (Potion potion : Registries.POTION) {
                additionalStacks.add(
                    PotionContentsComponent
                            .createStack(Items.POTION, Registries.POTION.getEntry(potion))
                );
                additionalStacks.add(
                    PotionContentsComponent
                            .createStack(Items.SPLASH_POTION, Registries.POTION.getEntry(potion))
                );
                additionalStacks.add(
                    PotionContentsComponent
                            .createStack(Items.LINGERING_POTION, Registries.POTION.getEntry(potion))
                );
                // This would create tipped arrows with the default potion duration
                // I haven't found a way to get the correct duration
//                additionalStacks.add(
//                    PotionContentsComponent
//                            .createStack(Items.TIPPED_ARROW, Registries.POTION.getEntry(potion))
//                );
//                ItemStack tippedArrows = new ItemStack(Items.TIPPED_ARROW);
            }
            stacks = Stream.concat(stacks, additionalStacks.stream());
        } else {
            // otherwise only show items from inventory
            PlayerInventoryAccessor inventory = (PlayerInventoryAccessor) MinecraftClient.getInstance().player.getInventory();
            ArrayList<ItemStack> distinctStacks = new ArrayList<>(Collections.emptyList());
            for (ItemStack stack : inventory.getMain()) {
                if (!stack.isEmpty()) {
                    boolean found = false;
                    Item item = stack.getItem();
                    List<String> enchantments = SearchBox.getEnchantments(stack);
                    List<String> effects = SearchBox.getEffects(stack);
                    for (ItemStack stack2 : distinctStacks) {
                        if (stack2.equals(stack) || (stack2.isOf(item)
                                && SearchBox.getEnchantments(stack2).equals(enchantments)
                                && SearchBox.getEffects(stack2).equals(effects))
                        ) { found = true; break; }
                    }
                    if (!found) distinctStacks.add(stack);
                }
            }
            stacks = distinctStacks.stream();
        }
        return stacks
                .map(stack -> {
                    String itemName = stack.getItemName().getString();
                    ArrayList<String> extraAttributes = new ArrayList<>(SearchBox.getEnchantments(stack));
                    extraAttributes.addAll(SearchBox.getEffects(stack));
                    if (SearchBox.getSong(stack) != null) extraAttributes.add(SearchBox.getSong(stack));
                    int score = 0;
                    for (String queryPart : query.split(" ")) {
                        int partScore = fuzzyScore.fuzzyScore(itemName, queryPart);
                        for (String attribute : extraAttributes) {
                            int attributeScore = fuzzyScore.fuzzyScore(attribute, queryPart);
                            if (attributeScore > partScore) partScore = attributeScore;
                        }
                        score += partScore;
                    }
                    int nameLength = itemName.length();
                    return new ScoredItem(stack, score, nameLength);
                })
                .filter(si -> si.score > 0)
                .sorted()
                .map(si -> si.item)
                .toList();
    }

    private record ScoredItem(ItemStack item, int score, int length) implements Comparable<ScoredItem> {

        @Override
        public int compareTo(@NotNull ScoredItem other) {
            // Higher score first
            if (this.score != other.score) return Integer.compare(other.score, this.score);
            // If scores are equal, prefer shorter names
            return Integer.compare(this.length, other.length);
        }
    }
}
