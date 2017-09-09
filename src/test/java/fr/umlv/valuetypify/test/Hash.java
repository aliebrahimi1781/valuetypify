package fr.umlv.valuetypify.test;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

public class Hash<K,V> extends AbstractMap<K,V> {
  HashEntry<?,?>[] entries;
  int size;
  
  public Hash() {
    entries = new HashEntry<?,?>[16];
  }

  @Override
  @SuppressWarnings("unchecked")
  public V get(Object key) {
    int hash = key.hashCode();  // implicit nullcheck
    HashEntry<?,?>[] entries = this.entries;
    int length = entries.length;
    int index = hash & (length - 1);
    for(;;index = nextIndex(index, length)) {
      HashEntry<?,?> entry = entries[index];
      if (entry.hash == hash && entry.key.equals(key)) {
        return (V)entry.value;
      }
      if (entry.value == null) {
        return null;
      }
    }
  }
  
  private static int nextIndex(int index, int length) {
    return (index == length - 1)? 0: index + 1;
  }
  
  @Override
  @SuppressWarnings("unchecked")
  public V put(K key, V value) {
    int hash = key.hashCode();  // implicit nullcheck
    Objects.requireNonNull(value);
    HashEntry<?,?>[] entries = this.entries;
    int length = entries.length;
    int index = hash & (length - 1);
    for(;;index = nextIndex(index, length)) {
      HashEntry<?,?> entry = entries[index];
      Object oldValue;
      if ((oldValue = entry.value) == null) {
        break;
      }
      if ((entry.hash == hash && entry.key.equals(key))) {
        entries[index] = new HashEntry<>(key, hash, value);
        return (V)oldValue;
      }
    }
    int size = this.size;
    if (length == (size << 1)) {
      return rehash(key, value);
    }
    entries[index] = new HashEntry<>(key, hash, value);
    this.size = size + 1;
    return null;
  }

  private V rehash(K key, V value) {
    HashEntry<?,?>[] oldEntries = this.entries;
    int oldLength = oldEntries.length;
    int newLength = oldLength << 1;
    HashEntry<?,?>[] newEntries = new HashEntry<?,?>[newLength];
    int mask = newLength - 1;
    loop: for(int i = 0; i < oldLength; i++) {
      HashEntry<?,?> oldEntry = oldEntries[i];
      Object oldValue = oldEntry.value;
      if (oldValue == null) {
        continue;
      }
      int oldHash = oldEntry.hash;
      int index = oldHash & mask;
      for(;;index = nextIndex(index, newLength)) {
        HashEntry<?,?> newEntry = newEntries[index];
        if (newEntry.value == null) {
          newEntries[index] = new HashEntry<>(oldEntry.key, oldHash, oldValue);
          continue loop;
        }
      }
    }
    
    int hash = key.hashCode();
    int index = hash & mask;
    for(;;index = nextIndex(index, newLength)) {
      HashEntry<?,?> newEntry = newEntries[index];
      if (newEntry.value == null) {
        newEntries[index] = new HashEntry<>(key, hash, value);
        
        this.entries = newEntries;
        size++;
        return null;
      }
    }
  }
  
  private Set<Entry<K, V>> entrySet;
  
  @Override
  public Set<Entry<K, V>> entrySet() {
    if (entrySet != null) {
      return entrySet;
    }
    return entrySet = new AbstractSet<Entry<K, V>>() {
      @Override
      public Iterator<Entry<K, V>> iterator() {
        HashEntry<?,?>[] entries = Hash.this.entries;
        return new Iterator<Map.Entry<K,V>>() {
          private int index;
          
          @Override
          public boolean hasNext() {
            return index < entries.length;
          }

          @Override
          @SuppressWarnings("unchecked")
          public Entry<K, V> next() {
            int index = this.index;
            HashEntry<?,?>[] _entries = entries;
            for(;;index++) {
              HashEntry<?,?> entry = _entries[index];
              if (entry.value == null) {
                continue;
              }
              this.index = index + 1;
              return (Entry<K, V>)entry;
            }
          }
        };
      }

      @Override
      public int size() {
        return Hash.this.size;
      }
    };
  }
  
  public static void main(String[] args) {
    new HashEntry<>(1, 1, 1);
    Hash<Integer, Integer> hash = new Hash<>();
    for(int i = 0; i < 50; i++) {
      hash.put(i, i);
    }
    System.out.println(hash.get(42));
  }
}
