package gr.uom.java.ast.decomposition.cfg;

import gr.uom.java.ast.LocalVariableDeclarationObject;
import gr.uom.java.ast.MethodObject;
import gr.uom.java.ast.ParameterObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.core.dom.VariableDeclaration;

public class PDG extends Graph {
	private CFG cfg;
	private PDGMethodEntryNode entryNode;
	private Map<CFGBranchNode, Set<CFGNode>> nestingMap;
	private Set<VariableDeclaration> variableDeclarations;
	
	public PDG(CFG cfg) {
		this.cfg = cfg;
		this.entryNode = new PDGMethodEntryNode(cfg.getMethod());
		this.nestingMap = new LinkedHashMap<CFGBranchNode, Set<CFGNode>>();
		for(GraphNode node : cfg.nodes) {
			CFGNode cfgNode = (CFGNode)node;
			if(cfgNode instanceof CFGBranchNode) {
				CFGBranchNode branchNode = (CFGBranchNode)cfgNode;
				nestingMap.put(branchNode, branchNode.getImmediatelyNestedNodesFromAST());
			}
		}
		this.variableDeclarations = new LinkedHashSet<VariableDeclaration>();
		ListIterator<ParameterObject> parameterIterator = cfg.getMethod().getParameterListIterator();
		while(parameterIterator.hasNext()) {
			ParameterObject parameter = parameterIterator.next();
			variableDeclarations.add(parameter.getSingleVariableDeclaration());
		}
		for(LocalVariableDeclarationObject localVariableDeclaration : cfg.getMethod().getLocalVariableDeclarations()) {
			variableDeclarations.add(localVariableDeclaration.getVariableDeclaration());
		}
		createControlDependenciesFromEntryNode();
		if(!nodes.isEmpty())
			createDataDependencies();
		GraphNode.resetNodeNum();
	}

	public PDGMethodEntryNode getEntryNode() {
		return entryNode;
	}

	public MethodObject getMethod() {
		return cfg.getMethod();
	}

	private void createControlDependenciesFromEntryNode() {
		for(GraphNode node : cfg.nodes) {
			CFGNode cfgNode = (CFGNode)node;
			if(!isNested(cfgNode)) {
				processCFGNode(entryNode, cfgNode, true);
			}
		}
	}

	private void processCFGNode(PDGNode previousNode, CFGNode cfgNode, boolean controlType) {
		if(cfgNode instanceof CFGBranchNode) {
			PDGControlPredicateNode predicateNode = new PDGControlPredicateNode(cfgNode, variableDeclarations);
			nodes.add(predicateNode);
			PDGControlDependence controlDependence = new PDGControlDependence(previousNode, predicateNode, controlType);
			edges.add(controlDependence);
			processControlPredicate(predicateNode);
		}
		else {
			PDGNode pdgNode = new PDGStatementNode(cfgNode, variableDeclarations);
			nodes.add(pdgNode);
			PDGControlDependence controlDependence = new PDGControlDependence(previousNode, pdgNode, controlType);
			edges.add(controlDependence);
		}
	}

	private void processControlPredicate(PDGControlPredicateNode predicateNode) {
		CFGBranchNode branchNode = (CFGBranchNode)predicateNode.getCFGNode();
		if(branchNode instanceof CFGBranchConditionalNode) {
			CFGBranchConditionalNode conditionalNode = (CFGBranchConditionalNode)branchNode;
			Set<CFGNode> nestedNodesInTrueControlFlow = conditionalNode.getImmediatelyNestedNodesInTrueControlFlow();
			for(CFGNode nestedNode : nestedNodesInTrueControlFlow) {
				processCFGNode(predicateNode, nestedNode, true);
			}
			Set<CFGNode> nestedNodesInFalseControlFlow = conditionalNode.getImmediatelyNestedNodesInFalseControlFlow();
			for(CFGNode nestedNode : nestedNodesInFalseControlFlow) {
				processCFGNode(predicateNode, nestedNode, false);
			}
		}
		else {
			Set<CFGNode> nestedNodes = nestingMap.get(branchNode);
			for(CFGNode nestedNode : nestedNodes) {
				processCFGNode(predicateNode, nestedNode, true);
			}
		}
	}

	private boolean isNested(CFGNode node) {
		for(CFGBranchNode key : nestingMap.keySet()) {
			Set<CFGNode> nestedNodes = nestingMap.get(key);
			if(nestedNodes.contains(node))
				return true;
		}
		return false;
	}

	private void createDataDependencies() {
		PDGNode firstPDGNode = (PDGNode)nodes.toArray()[0];
		for(VariableDeclaration variableInstruction : entryNode.definedVariables) {
			if(firstPDGNode.usesLocalVariable(variableInstruction)) {
				PDGDataDependence dataDependence = new PDGDataDependence(entryNode, firstPDGNode, variableInstruction);
				edges.add(dataDependence);
			}
			if(!firstPDGNode.definesLocalVariable(variableInstruction)) {
				dataDependenceSearch(entryNode, variableInstruction, firstPDGNode, new LinkedHashSet<PDGNode>());
			}
		}
		for(GraphNode node : nodes) {
			PDGNode pdgNode = (PDGNode)node;
			for(VariableDeclaration variableInstruction : pdgNode.definedVariables) {
				dataDependenceSearch(pdgNode, variableInstruction, pdgNode, new LinkedHashSet<PDGNode>());
			}
		}
	}

	private void dataDependenceSearch(PDGNode initialNode, VariableDeclaration variableInstruction,
			PDGNode currentNode, Set<PDGNode> visitedNodes) {
		if(visitedNodes.contains(currentNode))
			return;
		else
			visitedNodes.add(currentNode);
		CFGNode currentCFGNode = currentNode.getCFGNode();
		for(GraphEdge edge : currentCFGNode.outgoingEdges) {
			Flow flow = (Flow)edge;
			CFGNode dstCFGNode = (CFGNode)flow.dst;
			PDGNode dstPDGNode = dstCFGNode.getPDGNode();
			if(dstPDGNode.usesLocalVariable(variableInstruction)) {
				PDGDataDependence dataDependence = new PDGDataDependence(initialNode, dstPDGNode, variableInstruction);
				edges.add(dataDependence);
			}
			if(!dstPDGNode.definesLocalVariable(variableInstruction)) {
				dataDependenceSearch(initialNode, variableInstruction, dstPDGNode, visitedNodes);
			}
		}
	}

	public List<BasicBlock> getBasicBlocks() {
		return cfg.getBasicBlocks();
	}

	public Set<BasicBlock> forwardReachableBlocks(BasicBlock basicBlock) {
		return cfg.getBasicBlockCFG().forwardReachableBlocks(basicBlock);
	}

	//returns the node (branch or method entry) that directly dominates the leader of the block
	private PDGNode directlyDominates(BasicBlock block) {
		CFGNode leaderCFGNode = block.getLeader();
		PDGNode leaderPDGNode = leaderCFGNode.getPDGNode();
		for(GraphEdge edge : leaderPDGNode.incomingEdges) {
			PDGDependence dependence = (PDGDependence)edge;
			if(dependence instanceof PDGControlDependence) {
				PDGNode srcNode = (PDGNode)dependence.src;
				return srcNode;
			}
		}
		return null;
	}

	public Set<BasicBlock> dominatedBlocks(BasicBlock block) {
		PDGNode pdgNode = directlyDominates(block);
		return dominatedBlocks(pdgNode);
	}
	
	private Set<BasicBlock> dominatedBlocks(PDGNode branchNode) {
		Set<BasicBlock> dominatedBlocks = new LinkedHashSet<BasicBlock>();
		for(GraphEdge edge : branchNode.outgoingEdges) {
			PDGDependence dependence = (PDGDependence)edge;
			if(dependence instanceof PDGControlDependence) {
				PDGNode dstNode = (PDGNode)dependence.dst;
				BasicBlock dstBlock = dstNode.getBasicBlock();
				dominatedBlocks.add(dstBlock);
				PDGNode dstBlockLastNode = dstBlock.getLastNode().getPDGNode();
				if(dstBlockLastNode instanceof PDGControlPredicateNode)
					dominatedBlocks.addAll(dominatedBlocks(dstBlockLastNode));
			}
		}
		return dominatedBlocks;
	}

	public Set<BasicBlock> boundaryBlocks(PDGNode node) {
		Set<BasicBlock> boundaryBlocks = new LinkedHashSet<BasicBlock>();
		BasicBlock srcBlock = node.getBasicBlock();
		for(BasicBlock block : getBasicBlocks()) {
			Set<BasicBlock> forwardReachableBlocks = forwardReachableBlocks(block);
			Set<BasicBlock> dominatedBlocks = dominatedBlocks(block);
			Set<BasicBlock> intersection = new LinkedHashSet<BasicBlock>();
			intersection.addAll(forwardReachableBlocks);
			intersection.retainAll(dominatedBlocks);
			if(intersection.contains(srcBlock))
				boundaryBlocks.add(block);
		}
		return boundaryBlocks;
	}

	public Set<PDGNode> blockBasedRegion(BasicBlock block) {
		Set<PDGNode> regionNodes = new LinkedHashSet<PDGNode>();
		Set<BasicBlock> reachableBlocks = forwardReachableBlocks(block);
		for(BasicBlock reachableBlock : reachableBlocks) {
			List<CFGNode> blockNodes = reachableBlock.getAllNodes();
			for(CFGNode cfgNode : blockNodes) {
				regionNodes.add(cfgNode.getPDGNode());
			}
		}
		return regionNodes;
	}

	public Set<PDGSlice> getProgramDependenceSlices(PDGNode nodeCriterion, VariableDeclaration variableCriterion) {
		Set<PDGSlice> slices = new LinkedHashSet<PDGSlice>();
		Set<BasicBlock> boundaryBlocks = boundaryBlocks(nodeCriterion);
		for(BasicBlock boundaryBlock : boundaryBlocks) {
			PDGSlice slice = new PDGSlice(this, boundaryBlock, nodeCriterion, variableCriterion);
			slices.add(slice);
		}
		return slices;
	}

	public Set<PDGSlice> getProgramDependenceSlices(PDGNode nodeCriterion) {
		Set<PDGSlice> slices = new LinkedHashSet<PDGSlice>();
		Set<VariableDeclaration> examinedVariables = new LinkedHashSet<VariableDeclaration>();
		for(VariableDeclaration definedVariable : nodeCriterion.definedVariables) {
			if(!examinedVariables.contains(definedVariable)) {
				slices.addAll(getProgramDependenceSlices(nodeCriterion, definedVariable));
				examinedVariables.add(definedVariable);
			}
		}
		for(VariableDeclaration usedVariable : nodeCriterion.usedVariables) {
			if(!examinedVariables.contains(usedVariable)) {
				slices.addAll(getProgramDependenceSlices(nodeCriterion, usedVariable));
				examinedVariables.add(usedVariable);
			}
		}
		return slices;
	}

	public Set<PDGSlice> getAllProgramDependenceSlices() {
		Set<PDGSlice> slices = new LinkedHashSet<PDGSlice>();
		for(GraphNode node : nodes) {
			PDGNode pdgNode = (PDGNode)node;
			slices.addAll(getProgramDependenceSlices(pdgNode));
		}
		return slices;
	}
}