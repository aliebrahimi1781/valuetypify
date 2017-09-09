package fr.umlv.valuetypify.test;

import java.util.Map;

import jvm.internal.value.ValueCapableClass;

@ValueCapableClass
final class HashEntry<K, V> implements Map.Entry<K, V> {
  final K key;
  final int hash;
  final V value;

  HashEntry(K key, int hash, V value) {
    this.key = key;
    this.hash = hash;
    this.value = value;
  }
  @Override
  public K getKey() {
    return key;
  }
  @Override
  public V getValue() {
    return value;
  }
  @Override
  public V setValue(V value) {
    throw new UnsupportedOperationException();
  }
}