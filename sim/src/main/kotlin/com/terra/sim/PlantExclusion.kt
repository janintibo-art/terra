package com.terra.sim

/**
 * Cases du treillis végétal occupées par un VRAI arbre — lot 3.5-b.
 *
 * ## Le constat photo qui a créé ce fichier
 *
 * Le lot 3.5 pariait que les losanges des tuiles disparaîtraient dans les
 * couronnes des arbres instanciés. La première photo a tranché contre : la
 * couronne d'un feuillu commence à quatre mètres du sol, le losange en
 * fait jusqu'à sept — il dépasse SOUS le houppier, parfaitement lisible.
 *
 * ## La parade
 *
 * Le champ d'arbres publie ici les cases qu'il a réellement plantées ;
 * `TileMesh.emitOnePlant` consulte l'ensemble et n'émet pas de losange sur
 * une case occupée. Les tuiles déjà construites sont ensuite invalidées
 * (le flux les reconstruit par priorité, comme à un changement de monde).
 *
 * C'est un couplage forêt→tuiles À SENS UNIQUE et volontairement fruste :
 * les tuiles lisent un instantané, jamais l'inverse, et l'ensemble change
 * uniquement sur commande console — pas de reconstruction au fil de la
 * caméra tant que le suivi automatique n'existe pas.
 *
 * ## Déterminisme
 *
 * Les plantes des tuiles sont du RENDU : hors empreinte, hors sauvegarde.
 * Deux appareils avec la même graine et la même commande `foret` excluent
 * exactement les mêmes cases — le champ est lui-même déterministe.
 *
 * ## Limite connue
 *
 * Aux arêtes du cube, la face canonique d'une case vue par le champ peut
 * différer de celle de la tuile qui la dessine : le losange y survivrait.
 * Le champ s'arrête déjà à la face courante — la traversée de face
 * traitera les deux ensemble.
 */
object PlantExclusion {

    /** Instantané immuable, remplacé d'un bloc : les constructeurs de
     *  tuiles (fil de travail) lisent pendant que la console écrit. */
    @Volatile
    private var occupied: Set<Long> = emptySet()

    /** Révision, pour que l'appelant sache si une reconstruction s'impose. */
    @Volatile
    var revision: Int = 0
        private set

    fun key(face: Int, cellX: Long, cellY: Long): Long =
        (face.toLong() shl 58) or ((cellX and 0x1FF_FFFF) shl 29) or (cellY and 0x1FF_FFFF)

    fun contains(face: Int, cellX: Long, cellY: Long): Boolean {
        val set = occupied
        return set.isNotEmpty() && set.contains(key(face, cellX, cellY))
    }

    fun replace(keys: Set<Long>) {
        occupied = keys
        revision++
    }

    fun clear() {
        if (occupied.isEmpty()) return
        occupied = emptySet()
        revision++
    }

    val size: Int get() = occupied.size
}
