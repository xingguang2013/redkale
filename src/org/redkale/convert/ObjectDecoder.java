/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.convert;

import java.beans.*;
import static org.redkale.convert.ObjectEncoder.TYPEZERO;
import org.redkale.util.Creator;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.redkale.util.*;

/**
 *
 * @see http://www.redkale.org
 * @author zhangjx
 * @param <R>
 * @param <T>
 */
@SuppressWarnings("unchecked")
public final class ObjectDecoder<R extends Reader, T> implements Decodeable<R, T> {

    protected final Type type;

    protected final Class typeClass;

    protected Creator<T> creator;

    protected DeMember<R, T, ?>[] creatorConstructorMembers;

    protected DeMember<R, T, ?>[] members;

    protected Factory factory;

    private boolean inited = false;

    private final Object lock = new Object();

    protected ObjectDecoder(Type type) {
        this.type = ((type instanceof Class) && ((Class) type).isInterface()) ? Object.class : type;
        if (type instanceof ParameterizedType) {
            final ParameterizedType pt = (ParameterizedType) type;
            this.typeClass = (Class) pt.getRawType();
        } else {
            this.typeClass = (Class) type;
        }
        this.members = new DeMember[0];
    }

    public void init(final Factory factory) {
        this.factory = factory;
        try {
            if (type == Object.class) return;

            Class clazz = null;
            if (type instanceof ParameterizedType) {
                final ParameterizedType pts = (ParameterizedType) type;
                clazz = (Class) (pts).getRawType();
            } else if (!(type instanceof Class)) {
                throw new ConvertException("[" + type + "] is no a class");
            } else {
                clazz = (Class) type;
            }
            final Type[] virGenericTypes = this.typeClass.getTypeParameters();
            final Type[] realGenericTypes = (type instanceof ParameterizedType) ? ((ParameterizedType) type).getActualTypeArguments() : TYPEZERO;
            this.creator = factory.loadCreator(clazz);

            final Set<DeMember> list = new HashSet();
            final ConstructorProperties cps = ObjectEncoder.findConstructorProperties(this.creator);
            try {
                ConvertColumnEntry ref;
                for (final Field field : clazz.getFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    ref = factory.findRef(field);
                    if (ref != null && ref.ignore()) continue;
                    Type t = ObjectEncoder.makeGenericType(field.getGenericType(), virGenericTypes, realGenericTypes);
                    list.add(new DeMember(ObjectEncoder.createAttribute(factory, clazz, field, null, null), factory.loadDecoder(t)));
                }
                final boolean reversible = factory.isReversible();
                for (final Method method : clazz.getMethods()) {
                    if (Modifier.isStatic(method.getModifiers())) continue;
                    if (Modifier.isAbstract(method.getModifiers())) continue;
                    if (method.isSynthetic()) continue;
                    if (method.getName().length() < 4) continue;
                    if (!method.getName().startsWith("set")) continue;
                    if (method.getParameterTypes().length != 1) continue;
                    if (method.getReturnType() != void.class) continue;
                    if (reversible && (cps == null || !ObjectEncoder.contains(cps.value(), ObjectEncoder.readGetSetFieldName(method)))) {
                        boolean is = method.getParameterTypes()[0] == boolean.class || method.getParameterTypes()[0] == Boolean.class;
                        try {
                            clazz.getMethod(method.getName().replaceFirst("set", is ? "is" : "get"));
                        } catch (Exception e) {
                            continue;
                        }
                    }
                    ref = factory.findRef(method);
                    if (ref != null && ref.ignore()) continue;
                    Type t = ObjectEncoder.makeGenericType(method.getGenericParameterTypes()[0], virGenericTypes, realGenericTypes);
                    list.add(new DeMember(ObjectEncoder.createAttribute(factory, clazz, null, null, method), factory.loadDecoder(t)));
                }
                if (cps != null) { //可能存在某些构造函数中的字段名不存在setter方法
                    for (final String constructorField : cps.value()) {
                        boolean flag = false;
                        for (DeMember m : list) {
                            if (m.attribute.field().equals(constructorField)) {
                                flag = true;
                                break;
                            }
                        }
                        if (flag) continue;
                        //不存在setter方法
                        try {
                            Field f = clazz.getDeclaredField(constructorField);
                            Type t = ObjectEncoder.makeGenericType(f.getGenericType(), virGenericTypes, realGenericTypes);
                            list.add(new DeMember(ObjectEncoder.createAttribute(factory, clazz, f, null, null), factory.loadDecoder(t)));
                        } catch (NoSuchFieldException nsfe) { //不存在field， 可能存在getter方法
                            char[] fs = constructorField.toCharArray();
                            fs[0] = Character.toUpperCase(fs[0]);
                            String mn = new String(fs);
                            Method getter = null;
                            try {
                                getter = clazz.getMethod("get" + mn);
                            } catch (NoSuchMethodException ex) {
                                getter = clazz.getMethod("is" + mn);
                            }
                            Type t = ObjectEncoder.makeGenericType(getter.getGenericParameterTypes()[0], virGenericTypes, realGenericTypes);
                            list.add(new DeMember(ObjectEncoder.createAttribute(factory, clazz, null, getter, null), factory.loadDecoder(t)));
                        }
                    }
                }
                this.members = list.toArray(new DeMember[list.size()]);
                Arrays.sort(this.members);

                if (cps != null) {
                    final String[] fields = cps.value();
                    final DeMember<R, T, ?>[] ms = new DeMember[fields.length];
                    for (int i = 0; i < fields.length; i++) {
                        for (DeMember m : this.members) {
                            if (m.attribute.field().equals(fields[i])) {
                                ms[i] = m;
                                break;
                            }
                        }
                    }
                    this.creatorConstructorMembers = ms;
                }
            } catch (Exception ex) {
                throw new ConvertException(ex);
            }
        } finally {
            inited = true;
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    /**
     * 对象格式: [0x1][short字段个数][字段名][字段值]...[0x2]
     *
     * @param in
     * @return
     */
    @Override
    public final T convertFrom(final R in) {
        final String clazz = in.readClassName();
        if (clazz != null && !clazz.isEmpty()) return (T) factory.loadDecoder(factory.getEntity(clazz)).convertFrom(in);
        if (in.readObjectB() == Reader.SIGN_NULL) return null;
        if (!this.inited) {
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        if (this.creatorConstructorMembers == null) {  //空构造函数
            final T result = this.creator.create();
            final AtomicInteger index = new AtomicInteger();
            while (in.hasNext()) {
                DeMember member = in.readField(index, members);
                in.skipBlank();
                if (member == null) {
                    in.skipValue(); //跳过该属性的值
                } else {
                    member.read(in, result);
                }
                index.incrementAndGet();
            }
            in.readObjectE();
            return result;
        } else {  //带参数的构造函数
            final DeMember<R, T, ?>[] fields = this.creatorConstructorMembers;
            final Object[] constructorParams = new Object[fields.length];
            final Object[][] otherParams = new Object[this.members.length][2];
            final AtomicInteger index = new AtomicInteger();
            int oc = 0;
            while (in.hasNext()) {
                DeMember member = in.readField(index, members);
                in.skipBlank();
                if (member == null) {
                    in.skipValue(); //跳过该属性的值
                } else {
                    Object val = member.read(in);
                    boolean flag = true;
                    for (int i = 0; i < fields.length; i++) {
                        if (member == fields[i]) {
                            constructorParams[i] = val;
                            flag = false;
                            break;
                        }
                    }
                    if (flag) otherParams[oc++] = new Object[]{member.attribute, val};
                }
                index.incrementAndGet();
            }
            in.readObjectE();
            for (int i = 0; i < fields.length; i++) {
                final Class t = fields[i].attribute.type();
                if (t.isPrimitive() && constructorParams[i] == null) {
                    Object a = 0;
                    if (t == boolean.class) {
                        a = Boolean.FALSE;
                    } else if (t == byte.class) {
                        a = (byte) 0;
                    } else if (t == short.class) {
                        a = (short) 0;
                    } else if (t == char.class) {
                        a = (char) 0;
                    } else if (t == long.class) {
                        a = (long) 0L;
                    } else if (t == float.class) {
                        a = (float) 0.0f;
                    } else if (t == double.class) {
                        a = (double) 0.0;
                    }
                    constructorParams[i] = a;
                }
            }
            final T result = this.creator.create(constructorParams);
            for (int i = 0; i < oc; i++) {
                ((Attribute) otherParams[i][0]).set(result, otherParams[i][1]);
            }
            return result;
        }
    }

    @Override
    public final Type getType() {
        return this.type;
    }

    @Override
    public String toString() {
        return "ObjectDecoder{" + "type=" + type + ", members=" + Arrays.toString(members) + '}';
    }
}