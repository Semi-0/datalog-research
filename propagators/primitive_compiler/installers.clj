(ns propagators.primitive-compiler.installers)

(defn default-installers
  "Installer map (lazy resolve avoids compiler <-> stdlib cycles)."
  []
  {'prop/id (requiring-resolve 'propagators.stdlib.prop/id)
   'p:id (requiring-resolve 'propagators.stdlib.prop/id)
   'prop/+ (requiring-resolve 'propagators.stdlib.prop/+)
   'prop/- (requiring-resolve 'propagators.stdlib.prop/-)
   'prop/* (requiring-resolve 'propagators.stdlib.prop/*)
   'prop// (requiring-resolve 'propagators.stdlib.prop//)
   'prop/quot (requiring-resolve 'propagators.stdlib.prop/quot)
   'prop/<= (requiring-resolve 'propagators.stdlib.prop/<=)
   'prop/not (requiring-resolve 'propagators.stdlib.prop/not)
   'prop/and (requiring-resolve 'propagators.stdlib.prop/and)
   'prop/or (requiring-resolve 'propagators.stdlib.prop/or)
   'prop/nothing? (requiring-resolve 'propagators.stdlib.prop/nothing?)
   'prop/switch (requiring-resolve 'propagators.stdlib.prop/switch)
   'prop/when (requiring-resolve 'propagators.stdlib.prop/when)
   'closure/p:apply-closure (requiring-resolve 'propagators.closure/p:apply-closure)
   'closure/p:apply-network (requiring-resolve 'propagators.closure/p:apply-network)
   'closure/p:when-network (requiring-resolve 'propagators.closure/p:when-network)
   'closure/p:when-apply-network (requiring-resolve 'propagators.closure/p:when-apply-network)
   'closure/p:bind-network (requiring-resolve 'propagators.closure/p:bind-network)
   'cursor/p:car (requiring-resolve 'propagators.deprecated.cursor/p:car)
   'cursor/p:cdr (requiring-resolve 'propagators.deprecated.cursor/p:cdr)
   'obj/p:slot (requiring-resolve 'propagators.datastructures.compound-object/p:slot)
   'obj/p:slot-cursor (requiring-resolve 'propagators.datastructures.compound-object/p:slot-cursor)
   'decl/reduce-cursor (requiring-resolve 'propagators.declaration/reduce-cursor)
   'decl/reduce-slots (requiring-resolve 'propagators.declaration/reduce-slots)
   'recursive/p:recursive-compound
   (requiring-resolve 'propagators.recursive/p:recursive-compound)
   'recursive/p:self-refining-recursive-compound
   (requiring-resolve 'propagators.recursive/p:self-refining-recursive-compound)
   'recursive/p:accumulating-recursive-compound
   (requiring-resolve 'propagators.recursive/p:accumulating-recursive-compound)})
